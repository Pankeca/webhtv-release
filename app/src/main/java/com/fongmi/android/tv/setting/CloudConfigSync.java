package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.AuthApi;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 云端公共配置 / 个人配置备份：点播源、直播源与设置项。
 * 覆盖模式下只覆盖已有配置项字段，不删除本地已有源，避免误删追更与历史记录。
 */
public final class CloudConfigSync {

    public static final String PUBLIC_KEY = "public_config";
    private static final String BOOTSTRAP_KEY = "public_config_bootstrapped";
    private static final String TYPE = "config";
    private static final List<String> EXCLUDED_PREFIX = Arrays.asList(
            "account_", "cloud_", "viewing_", "playback_", "sync_", "config_", "wall_",
            "history_", "keep_", "update_", "download", "admin", "device", "token",
            "server", "webhook", "remote_", "spider_", "debug_", "crash_", "last_",
            "app_", "site_", "update_tracks", "doh_", "public_");

    public interface Callback {

        void success(String message);

        void error(String message);
    }

    private CloudConfigSync() {
    }

    public static boolean canSync() {
        return AccountStore.isLogin() && !AuthApi.server().isEmpty();
    }

    /** 当前本地点播源数量。 */
    public static int localVodCount() {
        return Config.getAll(0).size();
    }

    /** 当前本地直播源数量。 */
    public static int localLiveCount() {
        return Config.getAll(1).size();
    }

    public static JsonObject collect(boolean vod, boolean live, boolean settings) {
        JsonObject data = new JsonObject();
        if (vod) data.add("vod", App.gson().toJsonTree(Config.getAll(0)));
        if (live) data.add("live", App.gson().toJsonTree(Config.getAll(1)));
        if (settings) data.add("settings", collectSettings());
        return data;
    }

    /** 备份本地配置到当前账号云端（个人隔离）。 */
    public static void backup(boolean vod, boolean live, boolean settings, Callback callback) {
        if (!canSync()) {
            if (callback != null) callback.error("请先登录账号");
            return;
        }
        JsonObject data = collect(vod, live, settings);
        AuthApi.backupSave(TYPE, data, new AuthApi.Callback() {
            @Override
            public void success(JsonObject response) {
                CloudSyncStore.markConfigCount(vod ? Config.getAll(0).size() : CloudSyncStore.cloudVodCount(),
                        live ? Config.getAll(1).size() : CloudSyncStore.cloudLiveCount());
                if (callback != null) callback.success(backupSummary(data));
            }

            @Override
            public void error(String message) {
                if (callback != null) callback.error(message);
            }
        });
    }

    /** 从当前账号的个人备份恢复到本地。 */
    public static void pullPersonal(boolean vod, boolean live, boolean settings, int mode, Callback callback) {
        if (!canSync()) {
            if (callback != null) callback.error("请先登录账号");
            return;
        }
        AuthApi.backupLoad(TYPE, new AuthApi.Callback() {
            @Override
            public void success(JsonObject response) {
                JsonObject data = payload(response);
                Task.execute(() -> {
                    int[] result = apply(data, vod, live, settings, mode);
                    App.post(() -> {
                        if (callback != null) callback.success(summary("已从个人备份恢复", result));
                    });
                });
            }

            @Override
            public void error(String message) {
                if (callback != null) callback.error("暂无个人配置备份：" + message);
            }
        });
    }

    /** 管理员把本地配置发布到云端公共目录。 */
    public static void publish(boolean vod, boolean live, boolean settings, Callback callback) {
        if (!canSync()) {
            if (callback != null) callback.error("请先登录账号");
            return;
        }
        JsonObject data = collect(vod, live, settings);
        AuthApi.publicPut(PUBLIC_KEY, data.toString(), new AuthApi.Callback() {
            @Override
            public void success(JsonObject response) {
                if (callback != null) callback.success("公共配置已发布");
            }

            @Override
            public void error(String message) {
                if (callback != null) callback.error(message);
            }
        });
    }

    /** 公共配置只拉取，不要求登录：新装用户也能拿到管理员发布的默认源。 */
    public static void pullPublic(boolean vod, boolean live, boolean settings, int mode, Callback callback) {
        if (AuthApi.server().isEmpty()) {
            if (callback != null) callback.error("未配置云端服务地址");
            return;
        }
        AuthApi.publicGet(PUBLIC_KEY, new AuthApi.Callback() {
            @Override
            public void success(JsonObject response) {
                JsonObject data = parseContent(response);
                if (data == null) {
                    if (callback != null) callback.error("云端公共配置为空");
                    return;
                }
                Task.execute(() -> {
                    int[] result = apply(data, vod, live, settings, mode);
                    App.post(() -> {
                        if (callback != null) callback.success(summary("已从公共配置更新", result));
                    });
                });
            }

            @Override
            public void error(String message) {
                if (callback != null) callback.error("暂无公共配置：" + message);
            }
        });
    }

    /**
     * 新装首次启动且没有任何点播源时，自动拉取一次公共配置。
     * 只成功才写标记，失败下次启动会重试；回调保证只执行一次且在主线程。
     */
    public static void bootstrapIfNeeded(Runnable onReady) {
        boolean done = Prefers.getBoolean(BOOTSTRAP_KEY, false);
        if (done || !Config.getAll(0).isEmpty()) {
            if (!done) Prefers.put(BOOTSTRAP_KEY, true);
            if (onReady != null) onReady.run();
            return;
        }
        pullPublic(true, true, true, CloudSyncStore.MODE_CLOUD_FIRST, new Callback() {
            @Override
            public void success(String message) {
                Prefers.put(BOOTSTRAP_KEY, true);
                if (onReady != null) onReady.run();
            }

            @Override
            public void error(String message) {
                if (onReady != null) onReady.run();
            }
        });
    }

    /** 刷新云端数量统计，供个人中心展示。 */
    public static void refreshCounts() {
        refreshCounts(null);
    }

    public static void refreshCounts(Runnable onDone) {
        if (!AccountStore.isLogin()) {
            if (onDone != null) onDone.run();
            return;
        }
        AuthApi.backupLoad("keep", new AuthApi.Callback() {
            @Override
            public void success(JsonObject response) {
                JsonObject data = payload(response);
                if (data != null) {
                    int keeps = sizeOf(data, "keeps");
                    int tracks = sizeOf(data, "tracks");
                    CloudSyncStore.markKeepCount(keeps, tracks);
                }
                loadConfigCount(onDone);
            }

            @Override
            public void error(String message) {
                loadConfigCount(onDone);
            }
        });
    }

    private static void loadConfigCount(Runnable onDone) {
        AuthApi.backupLoad(TYPE, new AuthApi.Callback() {
            @Override
            public void success(JsonObject response) {
                JsonObject data = payload(response);
                if (data != null) {
                    int vod = sizeOf(data, "vod");
                    int live = sizeOf(data, "live");
                    if (vod > 0 || live > 0) CloudSyncStore.markConfigCount(vod, live);
                }
                if (onDone != null) onDone.run();
            }

            @Override
            public void error(String message) {
                if (onDone != null) onDone.run();
            }
        });
    }

    public static void markHistoryCount(int count) {
        CloudSyncStore.markHistoryCount(count);
    }

    /** 备份记录外层为 {type,updatedAt,data}，这里统一取出真正的业务数据。 */
    private static JsonObject payload(JsonObject response) {
        if (response == null || !response.has("data") || !response.get("data").isJsonObject()) return null;
        JsonObject record = response.getAsJsonObject("data");
        if (record.has("data") && record.get("data").isJsonObject()) return record.getAsJsonObject("data");
        return record;
    }

    private static JsonObject parseContent(JsonObject response) {
        try {
            JsonObject record = response.has("data") && response.get("data").isJsonObject() ? response.getAsJsonObject("data") : response;
            if (record.has("content") && record.get("content").isJsonPrimitive()) {
                return App.gson().fromJson(record.get("content").getAsString(), JsonObject.class);
            }
            if (record.has("data") && record.get("data").isJsonObject()) return record.getAsJsonObject("data");
            return record.has("vod") || record.has("live") || record.has("settings") ? record : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static int[] apply(JsonObject data, boolean vod, boolean live, boolean settings, int mode) {
        int[] result = new int[3];
        if (data == null) return result;
        if (vod) result[0] = applyConfigs(data, "vod", 0, mode);
        if (live) result[1] = applyConfigs(data, "live", 1, mode);
        if (settings) result[2] = applySettings(data, mode);
        if (result[0] > 0 || result[1] > 0) {
            if (result[0] > 0) CloudSyncStore.markConfigCount(localVodCount(), CloudSyncStore.cloudLiveCount());
            if (result[1] > 0) CloudSyncStore.markConfigCount(CloudSyncStore.cloudVodCount(), localLiveCount());
            App.post(ConfigEvent::common);
        }
        return result;
    }

    private static int applyConfigs(JsonObject data, String name, int type, int mode) {
        if (!data.has(name) || !data.get(name).isJsonArray()) return 0;
        List<Config> items;
        try {
            items = App.gson().fromJson(data.getAsJsonArray(name), new com.google.gson.reflect.TypeToken<List<Config>>() {
            }.getType());
        } catch (Throwable e) {
            return 0;
        }
        if (items == null) return 0;
        int changed = 0;
        for (Config item : items) {
            if (item == null || TextUtils.isEmpty(item.getUrl())) continue;
            Config local = Config.find(item.getUrl(), type);
            boolean exists = local.getId() != 0;
            if (exists && mode == CloudSyncStore.MODE_MERGE) continue;
            local.setName(TextUtils.isEmpty(item.getName()) ? local.getName() : item.getName());
            local.setLogo(TextUtils.isEmpty(item.getLogo()) ? local.getLogo() : item.getLogo());
            local.setHome(TextUtils.isEmpty(item.getHome()) ? local.getHome() : item.getHome());
            if (mode == CloudSyncStore.MODE_CLOUD_FIRST && !TextUtils.isEmpty(item.getJson())) local.setJson(item.getJson());
            local.type(type);
            local.save();
            changed++;
        }
        return changed;
    }

    private static JsonObject collectSettings() {
        JsonObject result = new JsonObject();
        try {
            for (Map.Entry<String, ?> entry : Prefers.getPrefers().getAll().entrySet()) {
                String key = entry.getKey();
                if (!isSyncedSetting(key)) continue;
                Object value = entry.getValue();
                if (value == null) continue;
                JsonObject item = new JsonObject();
                if (value instanceof Boolean) {
                    item.addProperty("t", "b");
                    item.addProperty("v", (Boolean) value);
                } else if (value instanceof Integer) {
                    item.addProperty("t", "i");
                    item.addProperty("v", (Integer) value);
                } else if (value instanceof Long) {
                    item.addProperty("t", "l");
                    item.addProperty("v", (Long) value);
                } else if (value instanceof Float) {
                    item.addProperty("t", "f");
                    item.addProperty("v", (Float) value);
                } else if (value instanceof String) {
                    item.addProperty("t", "s");
                    item.addProperty("v", (String) value);
                } else {
                    continue;
                }
                result.add(key, item);
            }
        } catch (Throwable e) {
            SpiderDebug.log("config-sync", "collect settings failed: %s", e.toString());
        }
        return result;
    }

    private static int applySettings(JsonObject data, int mode) {
        if (!data.has("settings") || !data.get("settings").isJsonObject()) return 0;
        JsonObject settings = data.getAsJsonObject("settings");
        int changed = 0;
        for (Map.Entry<String, JsonElement> entry : settings.entrySet()) {
            String key = entry.getKey();
            if (!isSyncedSetting(key)) continue;
            if (mode == CloudSyncStore.MODE_MERGE && Prefers.getPrefers().contains(key)) continue;
            try {
                JsonObject item = entry.getValue().getAsJsonObject();
                JsonElement value = item.get("v");
                if (value == null || value.isJsonNull()) continue;
                String type = item.has("t") ? item.get("t").getAsString() : "s";
                Object existing = Prefers.getPrefers().getAll().get(key);
                switch (type) {
                    case "b" -> Prefers.put(key, value.getAsBoolean());
                    case "i" -> Prefers.put(key, existing instanceof Long ? (Object) value.getAsLong() : (Object) value.getAsInt());
                    case "l" -> Prefers.put(key, value.getAsLong());
                    case "f" -> Prefers.put(key, value.getAsFloat());
                    default -> Prefers.put(key, value.getAsString());
                }
                changed++;
            } catch (Throwable ignored) {
            }
        }
        return changed;
    }

    private static boolean isSyncedSetting(String key) {
        if (TextUtils.isEmpty(key)) return false;
        for (String prefix : EXCLUDED_PREFIX) if (key.startsWith(prefix)) return false;
        return true;
    }

    private static int sizeOf(JsonObject data, String name) {
        return data.has(name) && data.get(name).isJsonArray() ? data.getAsJsonArray(name).size() : 0;
    }

    private static String summary(String prefix, int[] result) {
        List<String> parts = new ArrayList<>();
        if (result[0] > 0) parts.add("点播源 " + result[0] + " 项");
        if (result[1] > 0) parts.add("直播源 " + result[1] + " 项");
        if (result[2] > 0) parts.add("设置项 " + result[2] + " 项");
        return parts.isEmpty() ? prefix + "：已是最新" : prefix + "：" + String.join("、", parts);
    }

    /** 备份结果：把实际上传的点播源/直播源/设置项数量反馈给用户。 */
    private static String backupSummary(JsonObject data) {
        List<String> parts = new ArrayList<>();
        if (data.has("vod") && data.get("vod").isJsonArray()) parts.add("点播源 " + data.getAsJsonArray("vod").size() + " 项");
        if (data.has("live") && data.get("live").isJsonArray()) parts.add("直播源 " + data.getAsJsonArray("live").size() + " 项");
        if (data.has("settings") && data.get("settings").isJsonObject()) parts.add("设置项 " + data.getAsJsonObject("settings").size() + " 项");
        return parts.isEmpty() ? "已备份到云端" : "已备份到云端：" + String.join("、", parts);
    }
}
