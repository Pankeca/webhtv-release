package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.api.AuthApi;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 共享配置读取层：tvbox_shared_* 五个 key 的 schema 校验与本地映射。
 *
 * <p>契约见 docs/共享配置编辑契约.md。content 外层固定
 * {@code {"schema":"tvbox.shared.v1","items":[...]}}；点播/直播/弹幕额外允许
 * {@code activeUrl} 标记当前源（缺失时回退首条启用且地址合法的条目）。
 *
 * <p>读取顺序：新 key 优先；缺失、软删除或结构非法时回退旧的
 * {@code public_config.vod/live}。本类只读，不写服务端、不改本机配置。
 */
public final class SharedConfigClient {

    public static final String SCHEMA = "tvbox.shared.v1";
    public static final String KEY_VOD = "tvbox_shared_vod";
    public static final String KEY_LIVE = "tvbox_shared_live";
    public static final String KEY_SITES = "tvbox_shared_sites";
    public static final String KEY_WEB_RULES = "tvbox_shared_web_rules";
    public static final String KEY_DANMAKU = "tvbox_shared_danmaku";
    public static final String[] KEYS = {KEY_VOD, KEY_LIVE, KEY_SITES, KEY_WEB_RULES, KEY_DANMAKU};

    /** 旧公共配置 key：仅作为新 key 缺失时的只读回退。 */
    public static final String LEGACY_KEY = "public_config";

    public interface RecordCallback {

        void success(Record record);

        void missing();

        void error(String message);
    }

    /** 拉取结果直接是 CloudConfigSync.apply 能消费的 {vod/live/settings} 结构。 */
    public interface PullCallback {

        void success(JsonObject data);

        void error(String message);
    }

    /** 一条服务端共享配置记录（rev 是后续条件写的基准）。 */
    public static final class Record {

        public String key = "";
        public long rev;
        public boolean deleted;
        public String content = "";
        public String contentHash = "";
        public String updatedBy = "";
        public long updatedAt;
        private JsonObject root;

        /** 解析后的 content 根对象；content 为空或非法时为 null。 */
        public JsonObject root() {
            if (root == null && !TextUtils.isEmpty(content)) {
                try {
                    JsonElement element = JsonParser.parseString(content);
                    if (element != null && element.isJsonObject()) root = element.getAsJsonObject();
                } catch (Throwable ignored) {
                    root = null;
                }
            }
            return root;
        }

        public JsonArray items() {
            JsonObject root = root();
            return root != null && root.has("items") && root.get("items").isJsonArray()
                    ? root.getAsJsonArray("items") : new JsonArray();
        }

        /** schema 与 items 都合法才算可用；非法时调用方回退旧配置。 */
        public boolean isValid() {
            JsonObject root = root();
            return root != null && SCHEMA.equals(optString(root, "schema"))
                    && root.has("items") && root.get("items").isJsonArray();
        }

        public String activeUrl() {
            JsonObject root = root();
            return root == null ? "" : optString(root, "activeUrl");
        }
    }

    private SharedConfigClient() {
    }

    /** 读取单个 key；missing 表示不存在或已被软删除（普通用户视角看不到）。 */
    public static void load(String key, boolean includeDeleted, RecordCallback callback) {
        AuthApi.publicGetMeta(key, includeDeleted, new AuthApi.MetaCallback() {
            @Override
            public void success(JsonObject data) {
                if (callback != null) callback.success(parse(key, data));
            }

            @Override
            public void conflict(JsonObject current) {
                // GET 不会返回 409；按服务端当前版本兜底，避免面板卡住
                if (callback != null) callback.success(parse(key, current));
            }

            @Override
            public void missing() {
                if (callback != null) callback.missing();
            }

            @Override
            public void error(String message) {
                if (callback != null) callback.error(message);
            }
        });
    }

    public interface KeysCallback {

        void success(List<String> keys);

        void error(String message);
    }

    /**
     * 枚举某个共享 key 的所有分片。站点类超过 1MiB 时会拆成
     * {@code tvbox_shared_sites}、{@code _2}、{@code _3}…，主 key 排在最前。
     */
    public static void listKeys(String prefix, boolean includeDeleted, KeysCallback callback) {
        AuthApi.publicListMeta(prefix, includeDeleted, new AuthApi.Callback() {
            @Override
            public void success(JsonObject data) {
                List<String> keys = new ArrayList<>();
                if (data != null && data.has("keys") && data.get("keys").isJsonArray()) {
                    JsonArray array = data.getAsJsonArray("keys");
                    for (int i = 0; i < array.size(); i++) {
                        if (!array.get(i).isJsonPrimitive()) continue;
                        String key = array.get(i).getAsString();
                        if (shardIndex(prefix, key) >= 0) keys.add(key);
                    }
                }
                keys.sort((a, b) -> Integer.compare(shardIndex(prefix, a), shardIndex(prefix, b)));
                if (callback != null) callback.success(keys);
            }

            @Override
            public void error(String message) {
                if (callback != null) callback.error(message);
            }
        });
    }

    /** 分片序号：主 key=0，_2=1，_3=2…；不符合该模式的返回 -1。 */
    public static int shardIndex(String base, String key) {
        if (key == null || base == null || !key.startsWith(base)) return -1;
        String suffix = key.substring(base.length());
        if (suffix.isEmpty()) return 0;
        if (!suffix.startsWith("_")) return -1;
        String digits = suffix.substring(1);
        if (digits.isEmpty()) return -1;
        for (int i = 0; i < digits.length(); i++) if (!Character.isDigit(digits.charAt(i))) return -1;
        try {
            int value = Integer.parseInt(digits);
            return value >= 2 ? value - 1 : -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 分片 key：index 0 用主 key，之后依次 _2、_3… */
    public static String shardKey(String base, int index) {
        return index <= 0 ? base : base + "_" + (index + 1);
    }

    /**
     * 读取站点注入（含全部分片）并合并为一条虚拟记录，供列表与编辑器使用。
     * 分片顺序即合并顺序，activeUrl/rev/updatedBy 以主 key 为准。
     */
    public static void loadMergedSites(boolean includeDeleted, RecordCallback callback) {
        listKeys(KEY_SITES, includeDeleted, new KeysCallback() {
            @Override
            public void success(List<String> keys) {
                if (keys.isEmpty()) {
                    if (callback != null) callback.missing();
                    return;
                }
                final Record[] parts = new Record[keys.size()];
                final int[] done = {0};
                final boolean[] failed = {false};
                for (int i = 0; i < keys.size(); i++) {
                    final int index = i;
                    load(keys.get(i), includeDeleted, new RecordCallback() {
                        @Override
                        public void success(Record record) {
                            parts[index] = record;
                            advance();
                        }

                        @Override
                        public void missing() {
                            advance();
                        }

                        @Override
                        public void error(String message) {
                            failed[0] = true;
                            advance();
                        }

                        private void advance() {
                            if (++done[0] < keys.size()) return;
                            if (failed[0]) {
                                if (callback != null) callback.error("站点分片读取失败");
                                return;
                            }
                            Record merged = mergeSites(parts);
                            if (merged == null) {
                                if (callback != null) callback.missing();
                            } else if (callback != null) {
                                callback.success(merged);
                            }
                        }
                    });
                }
            }

            @Override
            public void error(String message) {
                if (callback != null) callback.error(message);
            }
        });
    }

    private static Record mergeSites(Record[] parts) {
        Record merged = new Record();
        merged.key = KEY_SITES;
        JsonArray items = new JsonArray();
        for (Record part : parts) {
            if (part == null) continue;
            if (merged.rev == 0 && !TextUtils.isEmpty(part.key) && KEY_SITES.equals(part.key)) {
                merged.rev = part.rev;
                merged.deleted = part.deleted;
                merged.updatedBy = part.updatedBy;
                merged.updatedAt = part.updatedAt;
            }
            // 软删除的分片不再参与合并，恢复或写入时由编辑器单独处理
            if (part.deleted) continue;
            JsonArray array = part.items();
            for (int i = 0; i < array.size(); i++) items.add(array.get(i).deepCopy());
        }
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        root.add("items", items);
        String active = "";
        for (Record part : parts) {
            if (part != null && KEY_SITES.equals(part.key)) {
                active = part.activeUrl();
                break;
            }
        }
        if (!TextUtils.isEmpty(active)) root.addProperty("activeUrl", active);
        merged.content = root.toString();
        return merged;
    }

    /**
     * 拉取点播/直播/设置：新 key 优先、旧 public_config 回退。
     *
     * <p>settings 不属于新共享结构（决策：仅 WebHTV 自用），因此仍从旧
     * public_config 读取，保持现有行为不变。所有回调都在主线程。
     */
    public static void loadForPull(final boolean vod, final boolean live, final boolean settings,
                                   final PullCallback callback) {
        final JsonObject data = new JsonObject();
        final boolean[] needLegacy = {false};
        final int total = (vod ? 1 : 0) + (live ? 1 : 0);
        if (total == 0) {
            loadLegacy(vod, live, settings, data, callback);
            return;
        }
        final AtomicInteger remaining = new AtomicInteger(total);
        final Runnable[] done = new Runnable[1];
        done[0] = () -> {
            if (remaining.decrementAndGet() > 0) return;
            if (needLegacy[0]) loadLegacy(vod, live, settings, data, callback);
            else deliver(data, callback);
        };
        if (vod) loadShared(KEY_VOD, "vod", data, needLegacy, done[0]);
        if (live) loadShared(KEY_LIVE, "live", data, needLegacy, done[0]);
    }

    /** 读取新 key；缺失/软删/结构非法/网络失败都标记回退，由旧配置兜底。 */
    private static void loadShared(String key, String name, JsonObject data, boolean[] needLegacy,
                                   Runnable done) {
        load(key, false, new RecordCallback() {
            @Override
            public void success(Record record) {
                if (record != null && record.isValid() && !record.deleted) {
                    data.add(name, configs(record));
                } else {
                    needLegacy[0] = true;
                }
                done.run();
            }

            @Override
            public void missing() {
                needLegacy[0] = true;
                done.run();
            }

            @Override
            public void error(String message) {
                // 新 key 读取失败时仍尝试旧配置，旧配置也失败才向用户报错
                needLegacy[0] = true;
                done.run();
            }
        });
    }

    private static void loadLegacy(boolean vod, boolean live, boolean settings, JsonObject data,
                                   PullCallback callback) {
        AuthApi.publicGet(LEGACY_KEY, new AuthApi.Callback() {
            @Override
            public void success(JsonObject response) {
                JsonObject legacy = parseContent(response);
                if (legacy == null) {
                    if (data.size() > 0) callback.success(data);
                    else callback.error("云端公共配置为空");
                    return;
                }
                if (vod && !data.has("vod")) copyArray(legacy, data, "vod");
                if (live && !data.has("live")) copyArray(legacy, data, "live");
                if (settings && legacy.has("settings") && legacy.get("settings").isJsonObject()) {
                    data.add("settings", legacy.getAsJsonObject("settings"));
                }
                if (data.size() == 0) callback.error("云端公共配置为空");
                else callback.success(data);
            }

            @Override
            public void error(String message) {
                if (data.size() > 0) callback.success(data);
                else callback.error("暂无公共配置：" + message);
            }
        });
    }

    /** 共享条目规范化成本地 Config 可解析的数组，供配置中心「拉取到本机」复用。 */
    public static JsonArray toConfigArray(Record record) {
        return configs(record);
    }

    /** 新 key 的 items 规范化成本地 Config 可解析的数组：过滤停用项、按 order 排序、activeUrl 置顶。 */
    private static JsonArray configs(Record record) {
        List<JsonObject> list = new ArrayList<>();
        JsonArray items = record.items();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isJsonObject()) continue;
            JsonObject item = items.get(i).getAsJsonObject();
            if (!isEnabled(item)) continue;
            if (!isHttp(optString(item, "url"))) continue;
            list.add(item);
        }
        // 稳定排序：order 缺省/非法时保持服务端原始顺序
        list.sort((a, b) -> Integer.compare(orderOf(a), orderOf(b)));
        String active = record.activeUrl();
        if (!TextUtils.isEmpty(active)) {
            for (int i = 0; i < list.size(); i++) {
                if (active.equals(optString(list.get(i), "url"))) {
                    list.add(0, list.remove(i));
                    break;
                }
            }
        }
        JsonArray result = new JsonArray();
        for (JsonObject item : list) result.add(normalizeConfig(item));
        return result;
    }

    /** 只保留 WebHTV Config 认识的字段，避免共享端的扩展字段干扰本地解析。 */
    private static JsonObject normalizeConfig(JsonObject item) {
        JsonObject out = new JsonObject();
        out.addProperty("name", optString(item, "name"));
        out.addProperty("url", optString(item, "url"));
        copyString(item, out, "logo");
        copyString(item, out, "home");
        copyString(item, out, "parse");
        // 冻结规则 D：点播/直播条目新增可选的 json 字段（本机 Config 原本就支持）
        copyString(item, out, "json");
        return out;
    }

    private static void copyString(JsonObject from, JsonObject to, String name) {
        String value = optString(from, name);
        if (!TextUtils.isEmpty(value)) to.addProperty(name, value);
    }

    private static int orderOf(JsonObject item) {
        try {
            if (item.has("order") && !item.get("order").isJsonNull()) {
                return item.get("order").getAsInt();
            }
        } catch (Throwable ignored) {
        }
        return Integer.MAX_VALUE;
    }

    private static boolean isEnabled(JsonObject item) {
        try {
            if (item.has("enabled") && !item.get("enabled").isJsonNull()) {
                return item.get("enabled").getAsBoolean();
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    private static void deliver(JsonObject data, PullCallback callback) {
        // data 为空也返回空对象，由上层统一提示「云端公共配置为空」
        callback.success(data);
    }

    private static void copyArray(JsonObject from, JsonObject to, String name) {
        if (from.has(name) && from.get(name).isJsonArray()) {
            to.add(name, from.getAsJsonArray(name).deepCopy());
        }
    }

    /** 把服务端返回的记录对象转成 Record（供写入层解析 409 的 current）。 */
    public static Record recordOf(String key, JsonObject data) {
        return parse(key, data);
    }

    private static Record parse(String key, JsonObject data) {
        Record record = new Record();
        record.key = key;
        if (data == null) return record;
        record.rev = longOf(data, "rev");
        record.deleted = boolOf(data, "deleted");
        record.content = optString(data, "content");
        record.contentHash = optString(data, "contentHash");
        record.updatedBy = optString(data, "updatedBy");
        record.updatedAt = longOf(data, "updatedAt");
        return record;
    }

    /** 旧 public_config 的外层可能是 {data:{content:"..."}}，统一取出业务对象。 */
    private static JsonObject parseContent(JsonObject response) {
        try {
            JsonObject record = response != null && response.has("data") && response.get("data").isJsonObject()
                    ? response.getAsJsonObject("data") : response;
            if (record == null) return null;
            if (record.has("content") && record.get("content").isJsonPrimitive()) {
                JsonElement element = JsonParser.parseString(record.get("content").getAsString());
                if (element != null && element.isJsonObject()) return element.getAsJsonObject();
            }
            if (record.has("data") && record.get("data").isJsonObject()) return record.getAsJsonObject("data");
            return record.has("vod") || record.has("live") || record.has("settings") ? record : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long longOf(JsonObject object, String name) {
        try {
            if (object.has(name) && !object.get(name).isJsonNull()) return object.get(name).getAsLong();
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static boolean boolOf(JsonObject object, String name) {
        try {
            if (object.has(name) && !object.get(name).isJsonNull()) return object.get(name).getAsBoolean();
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isHttp(String url) {
        return !TextUtils.isEmpty(url) && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private static String optString(JsonObject object, String name) {
        try {
            if (object != null && object.has(name) && !object.get(name).isJsonNull()) {
                JsonElement element = object.get(name);
                return element.isJsonPrimitive() ? element.getAsString() : element.toString();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }
}
