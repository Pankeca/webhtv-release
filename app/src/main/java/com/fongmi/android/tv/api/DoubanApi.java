package com.fongmi.android.tv.api;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 豆瓣热播（首页推荐用）。
 *
 * <p>数据源用豆瓣移动端的 rexxar 接口：返回体是 {@code {items:[{id,title,pic,rating:{value},url}]}}，
 * 比网页版好解析，也不用处理登录墙。必须带 Referer，否则会被跳转到一个空页面。
 *
 * <p>只用最朴素的 HttpURLConnection：这一路数据是「有就用、没有就空着」的锦上添花，
 * 不值得为它引一套额外的客户端，也不想和弹幕/更新的连接池互相影响。
 *
 * <p>拿到的条目没有站点 key，点击时按剧名丢给搜索，让用户自己选在哪个站点看——
 * 这也是 TVbox 那套推荐位的做法。
 */
public final class DoubanApi {

    private static final String TAG = "douban";
    private static final String MOVIE = "https://m.douban.com/rexxar/api/v2/subject_collection/movie_showing/items?start=%d&count=24&for_mobile=1";
    private static final String TV = "https://m.douban.com/rexxar/api/v2/subject_collection/tv_hot/items?start=%d&count=24&for_mobile=1";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private DoubanApi() {
    }

    /** page 从 1 开始；失败或解析不到时回调空列表，界面显示空态即可。 */
    public static void fetch(int page, Consumer<List<Vod>> callback) {
        Task.execute(() -> {
            List<Vod> items = new ArrayList<>();
            int start = Math.max(0, (page - 1) * 24);
            // 电影和剧集各拉一页拼起来，推荐位内容更全；任一路失败不影响另一路
            load(String.format(MOVIE, start), items);
            load(String.format(TV, start), items);
            App.post(() -> callback.accept(items));
        });
    }

    private static void load(String url, List<Vod> out) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", UA);
            connection.setRequestProperty("Referer", "https://m.douban.com/");
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                SpiderDebug.log(TAG, "http %s %s", connection.getResponseCode(), url);
                return;
            }
            parse(read(connection.getInputStream()), out);
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "load failed %s", e.toString());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void parse(String body, List<Vod> out) {
        if (TextUtils.isEmpty(body)) return;
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            // rexxar 接口的列表字段是 subject_collection_items（不是 items，实测 2026-10）
            JsonArray array = root.has("subject_collection_items") && root.get("subject_collection_items").isJsonArray()
                    ? root.getAsJsonArray("subject_collection_items")
                    : root.has("items") && root.get("items").isJsonArray() ? root.getAsJsonArray("items") : null;
            if (array == null) return;
            for (JsonElement element : array) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String title = text(item, "title");
                if (TextUtils.isEmpty(title)) continue;
                Vod vod = new Vod();
                vod.setId(text(item, "id"));
                vod.setName(title);
                vod.setPic(pic(item));
                String rate = rate(item);
                if (!TextUtils.isEmpty(rate)) vod.setRemarks(rate);
                out.add(vod);
            }
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "parse failed %s", e.toString());
        }
    }

    private static String text(JsonObject item, String key) {
        try {
            return item.has(key) && !item.get(key).isJsonNull() ? item.get(key).getAsString() : "";
        } catch (Throwable e) {
            return "";
        }
    }

    private static String pic(JsonObject item) {
        try {
            // 新版是 cover.url；旧版接口是 pic.normal，两种都兜住
            if (item.has("cover") && item.get("cover").isJsonObject()) {
                String url = text(item.getAsJsonObject("cover"), "url");
                if (!TextUtils.isEmpty(url)) return withReferer(url);
            }
            if (item.has("pic") && item.get("pic").isJsonObject()) return withReferer(text(item.getAsJsonObject("pic"), "normal"));
            return "";
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 豆瓣图床有防盗链：不带 Referer 直接 418。走 ImgUtil 的 {@code @Referer=} 约定补上。
     */
    private static String withReferer(String url) {
        if (TextUtils.isEmpty(url) || url.contains("@Referer=")) return url;
        return url + "@Referer=https://m.douban.com/";
    }

    private static String rate(JsonObject item) {
        try {
            if (!item.has("rating") || !item.get("rating").isJsonObject()) return "";
            JsonObject rating = item.getAsJsonObject("rating");
            String value = rating.has("value") && !rating.get("value").isJsonNull() ? rating.get("value").getAsString() : "";
            return TextUtils.isEmpty(value) || "0".equals(value) ? "" : value;
        } catch (Throwable e) {
            return "";
        }
    }

    private static String read(InputStream is) {
        try (InputStream input = is) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = input.read(buffer)) != -1) out.write(buffer, 0, len);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        }
    }
}
