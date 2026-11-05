package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.api.AuthApi;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 冻结规则 C：WebHTV 只读读取 TVBox 的 {@code tvbox_config} 备份，
 * 按 URL 并集合并到本地点播/直播源。
 *
 * <p>只做「补缺」：同 URL 已存在时保留本地名称与字段，不覆盖用户修改；
 * 用户在本机删掉过的 URL（{@link TvboxUrlTombstoneStore}）不会被对方备份复活。
 */
public final class TvboxConfigSync {

    private static final String BACKUP_TYPE = "tvbox_config";
    private static final String KEY_LAST_AT = "tvbox_config_import_at";
    /** 最近一次导入跳过的条数，便于排查「协议不兼容」造成的数量差异。 */
    private static final String KEY_SKIPPED = "tvbox_config_import_skipped";
    /** 本地已存在但 WebHTV 无法使用的地址（只标记，不删除用户数据）。 */
    private static final String KEY_UNUSABLE = "config_unusable_urls";
    private static final long INTERVAL_MS = TimeUnit.MINUTES.toMillis(30);

    private TvboxConfigSync() {
    }

    public static void schedule() {
        Task.execute(TvboxConfigSync::sync);
    }

    public static void sync() {
        if (!AccountStore.isLogin()) return;
        // 启动、登录都会触发，这里做节流，避免反复请求
        if (System.currentTimeMillis() - Prefers.getLong(KEY_LAST_AT, 0) < INTERVAL_MS) return;
        AuthApi.backupLoad(BACKUP_TYPE, new AuthApi.Callback() {
            @Override
            public void success(JsonObject data) {
                if (apply(data)) Prefers.put(KEY_LAST_AT, System.currentTimeMillis());
            }

            @Override
            public void error(String message) {
                // 对方没有这份备份时忽略：只读合并，不能影响本机配置
            }
        });
    }

    private static boolean apply(JsonObject data) {
        if (data == null) return false;
        JsonObject payload = data;
        if (payload.has("data") && payload.get("data").isJsonObject()) payload = payload.getAsJsonObject("data");
        JsonObject config = payload.has("config") && payload.get("config").isJsonObject() ? payload.getAsJsonObject("config") : payload;
        List<Entry> entries = new ArrayList<>();
        collect(config, "apiUrl", "apiHistory", 0, entries);
        collect(config, "liveUrl", "liveHistory", 1, entries);
        // 既有本地条目里若已有 WebHTV 无法使用的协议地址，只做「不可用」标记，
        // 不删除、不改名称，避免为了清洗而丢用户数据。
        int unusable = 0;
        for (int type : new int[]{0, 1}) {
            for (Config item : Config.getAll(type)) {
                if (!isSupported(item.getUrl())) {
                    markUnusable(item.getUrl(), type);
                    unusable++;
                }
            }
        }
        // 按 URL 并集只补缺：同地址已存在时保留本地字段
        int skipped = 0;
        boolean changed = false;
        for (Entry entry : entries) {
            if (TextUtils.isEmpty(entry.url)) continue;
            if (!isSupported(entry.url)) {
                // clan://、file://、content://、asset://、相对路径等本地协议 WebHTV 无法加载，
                // 直接跳过并累计条数，防止 TVBox 的本地源被并入后又被写进 WebHTV 自己的备份
                skipped++;
                continue;
            }
            if (Config.find(entry.url, entry.type) != null) continue;
            if (TvboxUrlTombstoneStore.isDeleted(entry.url, entry.type)) continue;
            Config.create(entry.type, entry.url, entry.name);
            changed = true;
        }
        Prefers.put(KEY_SKIPPED, skipped);
        if (skipped > 0 || unusable > 0) {
            SpiderDebug.log("tvbox-sync", "import skipped=%d, local unusable marked=%d", skipped, unusable);
        }
        return changed;
    }

    /** WebHTV 只支持 http(s) 源；其余协议/相对路径一律视为不可用。 */
    public static boolean isSupported(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String value = url.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("http://") || value.startsWith("https://");
    }

    /** 记录本地不可用地址（按 type+url 去重），供排查与避免反向备份污染。 */
    public static void markUnusable(String url, int type) {
        if (TextUtils.isEmpty(url)) return;
        try {
            JsonObject object = Prefers.getPrefers().getString(KEY_UNUSABLE, null) == null
                    ? new JsonObject()
                    : com.fongmi.android.tv.App.gson().fromJson(Prefers.getString(KEY_UNUSABLE, "{}"), JsonObject.class);
            if (object == null) object = new JsonObject();
            if (!object.has(identity(url, type))) {
                object.addProperty(identity(url, type), System.currentTimeMillis());
                Prefers.put(KEY_UNUSABLE, object.toString());
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean isMarkedUnusable(String url, int type) {
        if (TextUtils.isEmpty(url)) return false;
        try {
            JsonObject object = com.fongmi.android.tv.App.gson().fromJson(Prefers.getString(KEY_UNUSABLE, "{}"), JsonObject.class);
            return object != null && object.has(identity(url, type));
        } catch (Throwable e) {
            return false;
        }
    }

    /** 最近一次导入被协议白名单跳过的条数。 */
    public static int lastSkippedCount() {
        return Prefers.getInt(KEY_SKIPPED, 0);
    }

    private static String identity(String url, int type) {
        return type + "|" + (url == null ? "" : url.trim());
    }

    /** 兼容字符串、字符串数组、以及 {url,name} 对象数组三种写法。 */
    private static void collect(JsonObject config, String urlKey, String historyKey, int type, List<Entry> out) {
        add(config.get(urlKey), type, out);
        JsonElement history = config.get(historyKey);
        if (history != null && history.isJsonArray()) {
            JsonArray array = history.getAsJsonArray();
            for (JsonElement element : array) add(element, type, out);
        } else {
            add(history, type, out);
        }
    }

    private static void add(JsonElement element, int type, List<Entry> out) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonPrimitive()) {
            String url = element.getAsString();
            if (!TextUtils.isEmpty(url)) out.add(new Entry(type, url, host(url)));
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            String url = object.has("url") ? object.get("url").getAsString() : "";
            String name = object.has("name") ? object.get("name").getAsString() : "";
            if (!TextUtils.isEmpty(url)) out.add(new Entry(type, url, TextUtils.isEmpty(name) ? host(url) : name));
        }
    }

    private static String host(String url) {
        try {
            String value = url;
            int index = value.indexOf("://");
            if (index > 0) value = value.substring(index + 3);
            int end = value.indexOf('/');
            if (end > 0) value = value.substring(0, end);
            return value;
        } catch (Throwable e) {
            return "";
        }
    }

    private static final class Entry {
        private final int type;
        private final String url;
        private final String name;

        private Entry(int type, String url, String name) {
            this.type = type;
            this.url = url;
            this.name = name;
        }
    }
}
