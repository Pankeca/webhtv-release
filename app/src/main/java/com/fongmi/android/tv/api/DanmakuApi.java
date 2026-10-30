package com.fongmi.android.tv.api;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.DanmakuSource;
import com.fongmi.android.tv.player.danmaku.DanmakuUrlPolicy;
import com.fongmi.android.tv.setting.DanmakuSourceSetting;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Trans;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 弹幕搜索接口。支持多渠道来源：{@link DanmakuSourceSetting#getEnabledSources()} 的先后顺序即优先级。
 *
 * <p>同一个来源会被尝试两种请求形态，先来的有效结果胜出：
 * <ol>
 *   <li>{@code GET {base}?ac=danmu&name=&episode=} —— danmu_api 系公益源的标准形态
 *       （NewBox 内置源、绝大多数自建实例都走这个）；</li>
 *   <li>{@code POST {base}/danmaku} 表单 {@code name=&episode=} —— 项目原有接口形态。</li>
 * </ol>
 * 只发 POST 会打不通前者（实测返回 503 "error code: 1102"），只发 GET 会打不通后者
 * （实测返回 404「接口不存在」），所以必须两种都试。
 */
public class DanmakuApi {

    private static final String TAG = DanmakuApi.class.getSimpleName();
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    /** 多源合并搜索的首包等待上限：超过这个时间就把已拿到的结果先交付，避免被最慢的源拖住。 */
    private static final long FIRST_RESULT_TIMEOUT_MS = 6000L;

    /** 一次取数结果回调；无论成功失败都会回调一次，空列表表示这个来源没结果。 */
    public interface FetchCallback {

        void onResult(List<Danmaku> items);
    }

    /** 测速结果。 */
    public static final class Probe {

        public final boolean ok;
        public final long ms;
        public final int count;
        public final int status;
        public final String reason;

        Probe(boolean ok, long ms, int count, int status) {
            this(ok, ms, count, status, "");
        }

        Probe(boolean ok, long ms, int count, int status, String reason) {
            this.ok = ok;
            this.ms = ms;
            this.count = count;
            this.status = status;
            this.reason = reason == null ? "" : reason;
        }

        public String text() {
            if (ok) return count > 0 ? ms + "ms · " + count + " 条" : ms + "ms · 无数据";
            if (!reason.isEmpty()) return reason;
            return status <= 0 ? "超时" : "HTTP " + status;
        }
    }

    public static boolean canSearch() {
        return DanmakuSetting.isLoad() && DanmakuSetting.isAuto() && !DanmakuSourceSetting.getEnabledSources().isEmpty();
    }

    public static boolean canAutoSearch(List<Danmaku> siteDanmakus) {
        return canSearch() && (!DanmakuSetting.isSpiderFirst() || siteDanmakus == null || siteDanmakus.isEmpty());
    }

    /** 解析某个来源的响应，并按该来源自己的地址做 URL 规范化。 */
    public static List<Danmaku> arrayFrom(DanmakuSource source, String body) {
        return normalize(source, Danmaku.arrayFrom(body));
    }

    /**
     * 自动匹配：按来源顺序串行探测，取第一个有结果的来源。
     * 开启「合并所有来源」时退化成 {@link #searchAll} 取首条。
     */
    public static void search(String name, String episode, Consumer<Danmaku> found) {
        List<DanmakuSource> sources = DanmakuSourceSetting.getEnabledSources();
        if (sources.isEmpty() || found == null) return;
        if (DanmakuSourceSetting.isMergeAll()) {
            searchAll(name, episode, items -> {
                if (!items.isEmpty()) found.accept(items.get(0));
            }, null);
            return;
        }
        cancel();
        searchNext(sources, 0, name, episode, found);
    }

    private static void searchNext(List<DanmakuSource> sources, int index, String name, String episode, Consumer<Danmaku> found) {
        if (index >= sources.size()) return;
        DanmakuSource source = sources.get(index);
        int next = index + 1;
        fetch(source, name, episode, items -> {
            if (items.isEmpty()) searchNext(sources, next, name, episode, found);
            else App.post(() -> found.accept(items.get(0)));
        });
    }

    /**
     * 手动搜索：并行请求所有启用来源，合并去重后一次性回调。
     * 多来源时给没有自带渠道名的条目补上来源名，便于结果按来源分组。
     */
    public static void searchAll(String name, String episode, Consumer<List<Danmaku>> onResult, Runnable onEmpty) {
        List<DanmakuSource> sources = DanmakuSourceSetting.getEnabledSources();
        if (sources.isEmpty()) {
            if (onEmpty != null) App.post(onEmpty);
            return;
        }
        cancel();
        boolean label = sources.size() > 1;
        List<Danmaku> merged = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger pending = new AtomicInteger(sources.size());
        AtomicBoolean delivered = new AtomicBoolean();
        Runnable deliver = () -> {
            if (!delivered.compareAndSet(false, true)) return;
            List<Danmaku> result = dedup(new ArrayList<>(merged));
            if (onResult == null) return;
            if (result.isEmpty()) {
                if (onEmpty != null) App.post(onEmpty);
                else App.post(() -> onResult.accept(result));
                return;
            }
            App.post(() -> onResult.accept(result));
        };
        // 来源多的时候不能等最慢的那个：先到 6 秒就把手上已有的结果交付，慢的源不再影响首屏
        App.post(deliver, FIRST_RESULT_TIMEOUT_MS);
        for (DanmakuSource source : sources) {
            fetch(source, name, episode, items -> {
                collect(merged, items, label ? source : null);
                if (pending.decrementAndGet() == 0) deliver.run();
            });
        }
    }

    /**
     * 对单个来源发一次轻量搜索，用于界面里的「测速」。
     * 判定标准是「服务能正常应答」，不要求一定有结果——很多源只是恰好没有这部剧。
     */
    public static void probe(DanmakuSource source, Consumer<Probe> callback) {
        if (source == null || !source.isValid()) {
            if (callback != null) callback.accept(new Probe(false, 0, 0, 0));
            return;
        }
        List<Request> attempts = buildAttempts(source, "庆余年", "1");
        probeAttempt(source, attempts, 0, "", callback);
    }

    private static void probeAttempt(DanmakuSource source, List<Request> attempts, int index, String reason, Consumer<Probe> callback) {
        if (index >= attempts.size()) {
            if (callback != null) callback.accept(new Probe(false, 0, 0, 0, reason));
            return;
        }
        long started = android.os.SystemClock.elapsedRealtime();
        Call call = DanmakuHttp.newCall(source.isInsecure(), attempts.get(index));
        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                long ms = android.os.SystemClock.elapsedRealtime() - started;
                int status = response.code();
                boolean array = false;
                int count = 0;
                try (Response closeable = response) {
                    String body = closeable.body() == null ? "" : closeable.body().string();
                    String text = body == null ? "" : body.trim();
                    if (text.startsWith("[")) {
                        array = true;
                        count = arrayFrom(source, text).size();
                    }
                } catch (Exception ignored) {
                }
                if (status >= 200 && status < 300 && array) {
                    if (callback != null) callback.accept(new Probe(true, ms, count, status));
                } else {
                    probeAttempt(source, attempts, index + 1, "HTTP " + status, callback);
                }
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                probeAttempt(source, attempts, index + 1, describe(e), callback);
            }
        });
    }

    /** 把网络异常翻成用户能看懂的一句话，测速标签里直接显示。 */
    private static String describe(IOException e) {
        if (e instanceof javax.net.ssl.SSLException) return "证书不受信";
        if (e instanceof java.net.SocketTimeoutException) return "超时";
        if (e instanceof java.net.UnknownHostException) return "域名解析失败";
        if (e instanceof java.net.ConnectException) return "连不上";
        return "网络错误";
    }

    /** 依次尝试该来源的两种请求形态，任一种拿到非空结果就结束。 */
    private static void fetch(DanmakuSource source, String name, String episode, FetchCallback callback) {
        List<Request> attempts = buildAttempts(source, name, episode);
        if (attempts.isEmpty()) {
            callback.onResult(Collections.emptyList());
            return;
        }
        fetchAttempt(source, attempts, 0, callback);
    }

    private static void fetchAttempt(DanmakuSource source, List<Request> attempts, int index, FetchCallback callback) {
        if (index >= attempts.size()) {
            callback.onResult(Collections.emptyList());
            return;
        }
        Call call = DanmakuHttp.newCall(source.isInsecure(), attempts.get(index));
        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                List<Danmaku> items = Collections.emptyList();
                try (Response closeable = response) {
                    if (closeable.isSuccessful() && closeable.body() != null) {
                        items = arrayFrom(source, closeable.body().string());
                    }
                } catch (Exception ignored) {
                }
                if (!items.isEmpty()) {
                    if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku", "hit source=%s attempt=%d results=%d", source.getDisplayName(), index, items.size());
                    callback.onResult(items);
                } else {
                    fetchAttempt(source, attempts, index + 1, callback);
                }
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (SpiderDebug.isEnabled()) SpiderDebug.log("danmaku", "fail source=%s attempt=%d %s", source.getDisplayName(), index, e.getClass().getSimpleName());
                fetchAttempt(source, attempts, index + 1, callback);
            }
        });
    }

    /** 构造该来源的候选请求列表，按成功率排序。 */
    private static List<Request> buildAttempts(DanmakuSource source, String name, String episode) {
        List<Request> list = new ArrayList<>();
        if (source == null || !source.isValid()) return list;
        String url = source.getUrl();
        String title = Trans.t2s(false, name == null ? "" : name);
        String ep = Trans.t2s(false, episode == null ? "" : episode);
        try {
            if (url.contains("{name}") || url.contains("{episode}")) {
                String filled = url.replace("{name}", Uri.encode(title)).replace("{episode}", Uri.encode(ep));
                list.add(new Request.Builder().url(filled).header("User-Agent", UA).get().build());
                return list;
            }
            HttpUrl base = HttpUrl.parse(url);
            if (base != null) {
                HttpUrl get = base.newBuilder()
                        .addQueryParameter("ac", "danmu")
                        .addQueryParameter("name", title)
                        .addQueryParameter("episode", ep)
                        .build();
                list.add(new Request.Builder().url(get).header("User-Agent", UA).get().build());
            }
            FormBody body = new FormBody.Builder().add("name", title).add("episode", ep).build();
            list.add(new Request.Builder().url(getSearchUrl(url)).header("User-Agent", UA).post(body).build());
        } catch (Exception ignored) {
        }
        if (SpiderDebug.isEnabled()) for (int i = 0; i < list.size(); i++) SpiderDebug.log("danmaku", "attempt[%d] %s %s", i, source.getDisplayName(), DanmakuUrlPolicy.logSummary(String.valueOf(list.get(i).url())));
        return list;
    }

    private static String getSearchUrl(String url) {
        Uri uri = Uri.parse(url);
        List<String> segments = uri.getPathSegments();
        if (!segments.isEmpty() && "danmaku".equalsIgnoreCase(segments.get(segments.size() - 1))) return url;
        if (segments.size() > 1) return url;
        return uri.buildUpon().appendPath("danmaku").build().toString();
    }

    private static void collect(List<Danmaku> merged, List<Danmaku> items, DanmakuSource labelSource) {
        if (items == null || items.isEmpty()) return;
        for (Danmaku item : items) {
            if (labelSource != null && TextUtils.isEmpty(item.getApiSourceName())) item.setApiSourceName(labelSource.getDisplayName());
            merged.add(item);
        }
    }

    private static List<Danmaku> dedup(List<Danmaku> items) {
        return new ArrayList<>(new LinkedHashSet<>(items));
    }

    private static List<Danmaku> normalize(DanmakuSource source, List<Danmaku> items) {
        if (items.isEmpty()) return items;
        String api = source == null ? "" : getSearchUrl(source.getUrl());
        for (Danmaku item : items) item.setUrl(normalizeUrl(api, item.getUrl()));
        return items;
    }

    private static String normalizeUrl(String api, String url) {
        String normalized = DanmakuUrlPolicy.normalize(api, url);
        if (SpiderDebug.isEnabled() && !TextUtils.equals(url, normalized)) SpiderDebug.log("danmaku", "normalize result %s", DanmakuUrlPolicy.logSummary(normalized));
        return normalized;
    }

    public static void cancel() {
        DanmakuHttp.cancelAll();
    }
}
