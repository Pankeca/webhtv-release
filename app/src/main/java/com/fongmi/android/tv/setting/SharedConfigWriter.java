package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.api.AuthApi;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.DanmakuSource;
import com.fongmi.android.tv.web.ext.WebHomeExtensionSourceStore;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 共享配置写入层：内容构建、rev 条件写、记录级软删/恢复/彻底删除、管理员发布。
 *
 * <p>契约见 docs/共享配置编辑契约.md。所有写操作都带 rev，409 交给上层弹冲突框；
 * 站点注入超过 1MiB 时按 {@code tvbox_shared_sites} / {@code _2} / {@code _3…} 分片。
 * 普通用户不可达本类（入口仅在管理员口令解锁后显示）。
 */
public final class SharedConfigWriter {

    /** 服务端单 key content 上限（1MiB），站点分片按此阈值切分。 */
    public static final int MAX_CONTENT_BYTES = 1 << 20;
    /** 覆盖云端前的兜底备份前缀，与「应用到本机」的本地备份互不影响。 */
    public static final String CLOUD_BACKUP_PREFIX = "config_shared_cloud_backup_";
    private static final String DEFAULT_REASON = "编辑共享配置";

    public interface WriteCallback {

        void success();

        /** 409：key 为冲突的分片（单 key 类别即类别 key），current 为服务端最新记录。 */
        void conflict(String key, SharedConfigClient.Record current);

        void error(String message);
    }

    private SharedConfigWriter() {
    }

    /** 只有点播/直播/弹幕支持「当前源」标记。 */
    public static boolean supportsActive(String key) {
        return SharedConfigClient.KEY_VOD.equals(key) || SharedConfigClient.KEY_LIVE.equals(key)
                || SharedConfigClient.KEY_DANMAKU.equals(key);
    }

    public static String buildContent(List<JsonObject> items, String activeUrl) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SharedConfigClient.SCHEMA);
        JsonArray array = new JsonArray();
        for (JsonObject item : items) if (item != null) array.add(item.deepCopy());
        root.add("items", array);
        if (!TextUtils.isEmpty(activeUrl)) root.addProperty("activeUrl", activeUrl);
        return root.toString();
    }

    /** 单 key 保存；内容超限时直接报错（站点类走 {@link #saveSites}）。 */
    public static void save(String key, List<JsonObject> items, String activeUrl, long rev,
                            String reason, WriteCallback callback) {
        String content = buildContent(items, supportsActive(key) ? activeUrl : "");
        if (bytesOf(content) > MAX_CONTENT_BYTES) {
            if (callback != null) callback.error("内容超过 1MiB，请精简后再保存");
            return;
        }
        put(key, content, rev, reason, callback);
    }

    public static void deleteSoft(String key, long rev, String reason, WriteCallback callback) {
        AuthApi.publicDeleteSoft(key, rev, AccountStore.name(), reasonOf(reason), new AuthApi.MetaCallback() {
            @Override
            public void success(JsonObject data) {
                if (callback != null) callback.success();
            }

            @Override
            public void conflict(JsonObject current) {
                if (callback != null) callback.conflict(key, SharedConfigClient.recordOf(key, current));
            }

            @Override
            public void missing() {
                if (callback != null) callback.success();
            }

            @Override
            public void error(String message) {
                if (callback != null) callback.error(message);
            }
        });
    }

    public static void restore(String key, long rev, String reason, WriteCallback callback) {
        AuthApi.publicRestore(key, rev, AccountStore.name(), reasonOf(reason), new AuthApi.MetaCallback() {
            @Override
            public void success(JsonObject data) {
                if (callback != null) callback.success();
            }

            @Override
            public void conflict(JsonObject current) {
                if (callback != null) callback.conflict(key, SharedConfigClient.recordOf(key, current));
            }

            @Override
            public void missing() {
                if (callback != null) callback.error("记录不存在，无法恢复");
            }

            @Override
            public void error(String message) {
                if (callback != null) callback.error(message);
            }
        });
    }

    public static void purge(String key, long rev, String reason, WriteCallback callback) {
        AuthApi.publicPurge(key, rev, AccountStore.name(), reasonOf(reason), new AuthApi.MetaCallback() {
            @Override
            public void success(JsonObject data) {
                if (callback != null) callback.success();
            }

            @Override
            public void conflict(JsonObject current) {
                if (callback != null) callback.conflict(key, SharedConfigClient.recordOf(key, current));
            }

            @Override
            public void missing() {
                if (callback != null) callback.success();
            }

            @Override
            public void error(String message) {
                if (callback != null) callback.error(message);
            }
        });
    }

    /**
     * 站点分片保存：先枚举现有分片拿 rev，再按 1MiB 切分逐片条件写，
     * 最后把多余旧片软删除（不硬删，仍可从已删除列表恢复）。
     */
    public static void saveSites(List<JsonObject> items, String reason, WriteCallback callback) {
        SharedConfigClient.listKeys(SharedConfigClient.KEY_SITES, true, new SharedConfigClient.KeysCallback() {
            @Override
            public void success(List<String> keys) {
                collectRevs(keys, 0, new LinkedHashMap<>(), revs -> writeShards(pack(items), revs, reason, callback));
            }

            @Override
            public void error(String message) {
                if (callback != null) callback.error(message);
            }
        });
    }

    /** 整类软删除（站点类覆盖全部分片）。 */
    public static void deleteSites(String reason, WriteCallback callback) {
        eachSiteRecord((record, done) -> {
            if (record.deleted) done.run();
            else deleteSoft(record.key, record.rev, reason, new WriteCallback() {
                @Override
                public void success() {
                    done.run();
                }

                @Override
                public void conflict(String key, SharedConfigClient.Record current) {
                    if (callback != null) callback.conflict(key, current);
                }

                @Override
                public void error(String message) {
                    if (callback != null) callback.error(message);
                }
            });
        }, callback);
    }

    public static void restoreSites(String reason, WriteCallback callback) {
        eachSiteRecord((record, done) -> {
            if (!record.deleted) done.run();
            else restore(record.key, record.rev, reason, new WriteCallback() {
                @Override
                public void success() {
                    done.run();
                }

                @Override
                public void conflict(String key, SharedConfigClient.Record current) {
                    if (callback != null) callback.conflict(key, current);
                }

                @Override
                public void error(String message) {
                    if (callback != null) callback.error(message);
                }
            });
        }, callback);
    }

    public static void purgeSites(String reason, WriteCallback callback) {
        eachSiteRecord((record, done) -> {
            if (!record.deleted) done.run();
            else purge(record.key, record.rev, reason, new WriteCallback() {
                @Override
                public void success() {
                    done.run();
                }

                @Override
                public void conflict(String key, SharedConfigClient.Record current) {
                    if (callback != null) callback.conflict(key, current);
                }

                @Override
                public void error(String message) {
                    if (callback != null) callback.error(message);
                }
            });
        }, callback);
    }

    /** 汇总本机数据为新结构 items；迁移/发布时使用，不改动本机配置。 */
    public static List<JsonObject> localItems(String key) {
        switch (key) {
            case SharedConfigClient.KEY_VOD:
                return configItems(0);
            case SharedConfigClient.KEY_LIVE:
                return configItems(1);
            case SharedConfigClient.KEY_SITES:
                return siteItems();
            case SharedConfigClient.KEY_WEB_RULES:
                return ruleItems();
            case SharedConfigClient.KEY_DANMAKU:
                return danmakuItems();
            default:
                return new ArrayList<>();
        }
    }

    /** 本机当前源：点播/直播读 config_ 前缀，弹幕取第一条启用项。 */
    public static String localActiveUrl(String key) {
        if (SharedConfigClient.KEY_VOD.equals(key)) return Prefers.getString("config_0");
        if (SharedConfigClient.KEY_LIVE.equals(key)) return Prefers.getString("config_1");
        if (SharedConfigClient.KEY_DANMAKU.equals(key)) {
            for (DanmakuSource source : DanmakuSourceSetting.getSources()) {
                if (source != null && source.isEnabled() && source.isValid()) return source.getUrl();
            }
        }
        return "";
    }

    /** 覆盖云端前把服务端当前内容备份到本机配置前缀（不参与云同步）。 */
    public static void backupCloud(String key, SharedConfigClient.Record record) {
        JsonObject backup = new JsonObject();
        backup.addProperty("at", System.currentTimeMillis());
        backup.addProperty("key", key);
        backup.addProperty("rev", record == null ? 0 : record.rev);
        backup.addProperty("content", record == null ? "" : record.content);
        Prefers.put(CLOUD_BACKUP_PREFIX + key, backup.toString());
    }

    private static void put(String key, String content, long rev, String reason, WriteCallback callback) {
        AuthApi.publicPutConditional(key, content, rev, AccountStore.name(), reasonOf(reason),
                new AuthApi.MetaCallback() {
                    @Override
                    public void success(JsonObject data) {
                        if (callback != null) callback.success();
                    }

                    @Override
                    public void conflict(JsonObject current) {
                        if (callback != null) callback.conflict(key, SharedConfigClient.recordOf(key, current));
                    }

                    @Override
                    public void missing() {
                        if (callback != null) callback.error("记录不存在");
                    }

                    @Override
                    public void error(String message) {
                        if (callback != null) callback.error(message);
                    }
                });
    }

    private static interface CollectCallback {

        void done(Map<String, Long> revs);
    }

    private static void collectRevs(List<String> keys, int index, Map<String, Long> revs,
                                    CollectCallback callback) {
        if (index >= keys.size()) {
            callback.done(revs);
            return;
        }
        String key = keys.get(index);
        SharedConfigClient.load(key, true, new SharedConfigClient.RecordCallback() {
            @Override
            public void success(SharedConfigClient.Record record) {
                revs.put(key, record.rev);
                collectRevs(keys, index + 1, revs, callback);
            }

            @Override
            public void missing() {
                collectRevs(keys, index + 1, revs, callback);
            }

            @Override
            public void error(String message) {
                // 读不到 rev 时按 0 继续，若服务端实际存在会由 409 拦下
                revs.put(key, 0L);
                collectRevs(keys, index + 1, revs, callback);
            }
        });
    }

    private static void writeShards(List<String> contents, Map<String, Long> revs, String reason,
                                    WriteCallback callback) {
        writeShard(contents, revs, 0, reason, callback);
    }

    private static void writeShard(List<String> contents, Map<String, Long> revs, int index,
                                   String reason, WriteCallback callback) {
        String base = SharedConfigClient.KEY_SITES;
        if (index < contents.size()) {
            String key = SharedConfigClient.shardKey(base, index);
            long rev = revs.getOrDefault(key, 0L);
            put(key, contents.get(index), rev, reason, new WriteCallback() {
                @Override
                public void success() {
                    writeShard(contents, revs, index + 1, reason, callback);
                }

                @Override
                public void conflict(String conflictKey, SharedConfigClient.Record current) {
                    if (callback != null) callback.conflict(conflictKey, current);
                }

                @Override
                public void error(String message) {
                    if (callback != null) callback.error(message);
                }
            });
            return;
        }
        // 新内容片数变少：剩余旧分片软删除，保留可恢复能力
        List<String> stale = new ArrayList<>();
        for (String key : revs.keySet()) {
            if (SharedConfigClient.shardIndex(base, key) >= contents.size()) stale.add(key);
        }
        deleteStale(stale, revs, 0, reason, callback);
    }

    private static void deleteStale(List<String> keys, Map<String, Long> revs, int index, String reason,
                                    WriteCallback callback) {
        if (index >= keys.size()) {
            if (callback != null) callback.success();
            return;
        }
        String key = keys.get(index);
        deleteSoft(key, revs.getOrDefault(key, 0L), reason, new WriteCallback() {
            @Override
            public void success() {
                deleteStale(keys, revs, index + 1, reason, callback);
            }

            @Override
            public void conflict(String conflictKey, SharedConfigClient.Record current) {
                if (callback != null) callback.conflict(conflictKey, current);
            }

            @Override
            public void error(String message) {
                if (callback != null) callback.error(message);
            }
        });
    }

    /** 按 1MiB 上限贪心切分；单条超限也单独成片，交给服务端 413 暴露问题。 */
    private static List<String> pack(List<JsonObject> items) {
        List<String> shards = new ArrayList<>();
        List<JsonObject> current = new ArrayList<>();
        for (JsonObject item : items) {
            if (item == null) continue;
            current.add(item);
            if (bytesOf(buildContent(current, "")) <= MAX_CONTENT_BYTES) continue;
            current.remove(current.size() - 1);
            if (current.isEmpty()) {
                shards.add(buildContent(new ArrayList<>(List.of(item)), ""));
            } else {
                shards.add(buildContent(current, ""));
                current = new ArrayList<>();
                current.add(item);
            }
        }
        if (!current.isEmpty()) shards.add(buildContent(current, ""));
        if (shards.isEmpty()) shards.add(buildContent(new ArrayList<>(), ""));
        return shards;
    }

    private static interface RecordTask {

        void run(SharedConfigClient.Record record, Runnable done);
    }

    /** 逐个读取站点分片（含软删除）后串行执行任务；读不到的片跳过。 */
    private static void eachSiteRecord(RecordTask task, WriteCallback callback) {
        SharedConfigClient.listKeys(SharedConfigClient.KEY_SITES, true, new SharedConfigClient.KeysCallback() {
            @Override
            public void success(List<String> keys) {
                List<SharedConfigClient.Record> records = new ArrayList<>();
                collectRecords(keys, 0, records, () -> runRecords(records, 0, task, callback));
            }

            @Override
            public void error(String message) {
                if (callback != null) callback.error(message);
            }
        });
    }

    private static void collectRecords(List<String> keys, int index, List<SharedConfigClient.Record> records,
                                       Runnable done) {
        if (index >= keys.size()) {
            done.run();
            return;
        }
        String key = keys.get(index);
        SharedConfigClient.load(key, true, new SharedConfigClient.RecordCallback() {
            @Override
            public void success(SharedConfigClient.Record record) {
                records.add(record);
                collectRecords(keys, index + 1, records, done);
            }

            @Override
            public void missing() {
                collectRecords(keys, index + 1, records, done);
            }

            @Override
            public void error(String message) {
                collectRecords(keys, index + 1, records, done);
            }
        });
    }

    private static void runRecords(List<SharedConfigClient.Record> records, int index, RecordTask task,
                                   WriteCallback callback) {
        if (index >= records.size()) {
            if (callback != null) callback.success();
            return;
        }
        task.run(records.get(index), () -> runRecords(records, index + 1, task, callback));
    }

    private static List<JsonObject> configItems(int type) {
        List<JsonObject> items = new ArrayList<>();
        List<Config> configs = Config.getAll(type);
        for (int i = 0; i < configs.size(); i++) {
            Config config = configs.get(i);
            if (config == null || TextUtils.isEmpty(config.getUrl())) continue;
            JsonObject item = new JsonObject();
            item.addProperty("name", config.getName() == null ? "" : config.getName());
            item.addProperty("url", config.getUrl());
            copyString(item, "logo", config.getLogo());
            copyString(item, "home", config.getHome());
            copyString(item, "parse", config.getParse());
            item.addProperty("enabled", true);
            item.addProperty("order", items.size());
            items.add(item);
        }
        return items;
    }

    /** 站点注入只同步元数据（key/name/type/api/ext），页面文件与图标仍留在本机。 */
    private static List<JsonObject> siteItems() {
        List<JsonObject> items = new ArrayList<>();
        for (CustomCspSetting.Item site : CustomCspSetting.load().getItems()) {
            if (site == null || !site.isEnabled() || site.isOther() || site.isLive()) continue;
            String api = site.getApi();
            if (TextUtils.isEmpty(site.getKey()) || TextUtils.isEmpty(api)) continue;
            JsonObject item = new JsonObject();
            item.addProperty("key", site.getKey());
            item.addProperty("name", site.getName());
            item.addProperty("type", site.getType());
            item.addProperty("api", api);
            // ext 在本机是字符串；只有合法 JSON 对象才写入，避免破坏契约里的对象类型
            JsonObject ext = parseObject(site.getExt());
            if (ext != null) item.add("ext", ext);
            items.add(item);
        }
        return items;
    }

    private static List<JsonObject> ruleItems() {
        List<JsonObject> items = new ArrayList<>();
        for (WebHomeExtensionSourceStore.Entry entry : WebHomeExtensionSourceStore.list()) {
            if (entry == null) continue;
            JsonObject raw = parseObject(entry.getRaw());
            String code = raw == null ? "" : optString(raw, "code");
            if (TextUtils.isEmpty(code)) continue;
            String runAt = optString(raw, "runAt");
            if (!"start".equals(runAt) && !"end".equals(runAt)) runAt = "end";
            JsonObject item = new JsonObject();
            item.addProperty("name", entry.getName());
            item.addProperty("type", "js");
            item.addProperty("content", code);
            item.addProperty("match", matchOf(raw));
            item.addProperty("exclude", "");
            item.addProperty("runAt", runAt);
            item.addProperty("enabled", entry.isEnabled());
            item.addProperty("siteKey", entry.getSiteKey());
            items.add(item);
        }
        return items;
    }

    private static List<JsonObject> danmakuItems() {
        List<JsonObject> items = new ArrayList<>();
        for (DanmakuSource source : DanmakuSourceSetting.getSources()) {
            if (source == null || TextUtils.isEmpty(source.getUrl())) continue;
            JsonObject item = new JsonObject();
            item.addProperty("name", source.getName() == null ? "" : source.getName());
            item.addProperty("url", source.getUrl());
            item.addProperty("enabled", source.isEnabled());
            items.add(item);
        }
        return items;
    }

    private static String matchOf(JsonObject raw) {
        if (raw == null) return "*";
        JsonElement element = raw.get("cspKeyRegex");
        if (element == null || !element.isJsonArray()) return "*";
        JsonArray array = element.getAsJsonArray();
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonPrimitive()) continue;
            String value = array.get(i).getAsString().trim();
            if (!TextUtils.isEmpty(value)) values.add(value);
        }
        return values.isEmpty() ? "*" : String.join("\n", values);
    }

    private static JsonObject parseObject(String text) {
        if (TextUtils.isEmpty(text) || !text.trim().startsWith("{")) return null;
        try {
            JsonElement element = JsonParser.parseString(text);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String optString(JsonObject object, String name) {
        try {
            JsonElement element = object == null ? null : object.get(name);
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return "";
            return element.getAsString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void copyString(JsonObject target, String name, String value) {
        if (!TextUtils.isEmpty(value)) target.addProperty(name, value);
    }

    private static String reasonOf(String reason) {
        return TextUtils.isEmpty(reason) ? DEFAULT_REASON : reason;
    }

    private static int bytesOf(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }
}
