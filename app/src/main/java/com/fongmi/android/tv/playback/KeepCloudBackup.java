package com.fongmi.android.tv.playback;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.AuthApi;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.AccountStore;
import com.fongmi.android.tv.setting.CloudSyncStore;
import com.fongmi.android.tv.track.UpdateTrack;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Snapshot backup of favorites and update tracking ("收藏追更"). The snapshot
 * lives in the user's cloud account, keyed by uid, so a future second app can
 * reuse the same backup payload.
 */
public final class KeepCloudBackup {

    private static final String TYPE = "keep";
    private static final Runnable DEBOUNCED = () -> Task.execute(() -> backup(false));

    private KeepCloudBackup() {
    }

    public static boolean canBackup() {
        return AccountStore.isLogin() && !AuthApi.server().isEmpty() && CloudSyncStore.isKeepEnabled();
    }

    public static void schedule(long delayMs) {
        if (!canBackup()) return;
        App.post(DEBOUNCED, delayMs);
    }

    public static void backup(boolean notify) {
        if (!canBackup()) return;
        try {
            List<Keep> keeps = AppDatabase.get().getKeepDao().findAll();
            List<UpdateTrack> tracks = UpdateTrack.list();
            JsonObject data = new JsonObject();
            data.add("keeps", App.gson().toJsonTree(keeps));
            data.add("tracks", App.gson().toJsonTree(tracks));
            final int keepCount = keeps.size();
            final int trackCount = tracks.size();
            AuthApi.backupSave(TYPE, data, new AuthApi.Callback() {
                @Override
                public void success(JsonObject response) {
                    CloudSyncStore.markKeepCount(keepCount, trackCount);
                }

                @Override
                public void error(String message) {
                    SpiderDebug.log("keep-backup", "backup failed: %s", message);
                }
            });
        } catch (Throwable e) {
            SpiderDebug.log("keep-backup", "backup failed: %s", e.toString());
        }
    }

    /** Restores the cloud snapshot only when this device has nothing local yet. */
    public static void restoreIfEmpty() {
        restore(false, null);
    }

    /** 增量双向同步：云端独有的追更/追更跟踪补到本地，随后把两侧并集回写云端（本地同 key 优先）。 */
    public static void merge(Runnable onDone) {
        if (!canBackup()) {
            if (onDone != null) App.post(onDone);
            return;
        }
        Task.execute(() -> AuthApi.backupLoad(TYPE, new AuthApi.Callback() {
            @Override
            public void success(JsonObject response) {
                Task.execute(() -> {
                    mergeCloud(response);
                    backup(false);
                    App.post(() -> {
                        if (onDone != null) onDone.run();
                    });
                });
            }

            @Override
            public void error(String message) {
                // 云端暂无备份时按本地快照上传
                backup(false);
                App.post(() -> {
                    if (onDone != null) onDone.run();
                });
            }
        }));
    }

    private static void mergeCloud(JsonObject response) {
        try {
            JsonObject data = payload(response);
            if (data == null) return;
            Set<String> keepKeys = new HashSet<>();
            for (Keep keep : AppDatabase.get().getKeepDao().findAll()) keepKeys.add(keep.getKey());
            Set<String> trackKeys = new HashSet<>();
            for (UpdateTrack track : UpdateTrack.list()) trackKeys.add(track.getKey());
            boolean changed = false;
            if (data.has("keeps") && data.get("keeps").isJsonArray()) {
                List<Keep> items = App.gson().fromJson(data.getAsJsonArray("keeps"), new com.google.gson.reflect.TypeToken<List<Keep>>() {
                }.getType());
                if (items != null) {
                    for (Keep item : items) {
                        if (item == null || TextUtils.isEmpty(item.getKey())) continue;
                        if (!keepKeys.add(item.getKey())) continue;
                        AppDatabase.get().getKeepDao().insertOrUpdate(item);
                        changed = true;
                    }
                }
            }
            if (data.has("tracks") && data.get("tracks").isJsonArray()) {
                for (JsonElement element : data.getAsJsonArray("tracks")) {
                    try {
                        UpdateTrack item = App.gson().fromJson(element, UpdateTrack.class);
                        if (item == null || TextUtils.isEmpty(item.getKey())) continue;
                        if (!trackKeys.add(item.getKey())) continue;
                        UpdateTrack.save(item);
                        changed = true;
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (changed) RefreshEvent.keep();
        } catch (Throwable e) {
            SpiderDebug.log("keep-backup", "merge failed: %s", e.toString());
        }
    }

    private static JsonObject payload(JsonObject response) {
        if (response == null || !response.has("data") || !response.get("data").isJsonObject()) return null;
        JsonObject record = response.getAsJsonObject("data");
        if (record.has("data") && record.get("data").isJsonObject()) return record.getAsJsonObject("data");
        return record;
    }

    /** 从云端恢复追更/追更跟踪；force=true 时覆盖本地（先清空本地记录）。 */
    public static void restore(boolean force, Runnable onDone) {
        if (!AccountStore.isLogin()) return;
        Task.execute(() -> {
            if (!force) {
                try {
                    if (!AppDatabase.get().getKeepDao().findAll().isEmpty()) return;
                    if (!UpdateTrack.list().isEmpty()) return;
                } catch (Throwable e) {
                    return;
                }
            }
            AuthApi.backupLoad(TYPE, new AuthApi.Callback() {
                @Override
                public void success(JsonObject response) {
                    Task.execute(() -> {
                        apply(response, force);
                        App.post(() -> {
                            if (onDone != null) onDone.run();
                        });
                    });
                }

                @Override
                public void error(String message) {
                    SpiderDebug.log("keep-backup", "restore skipped: %s", message);
                    App.post(() -> {
                        if (onDone != null) onDone.run();
                    });
                }
            });
        });
    }

    private static void apply(JsonObject response, boolean replace) {
        try {
            JsonObject record = response.has("data") && response.get("data").isJsonObject() ? response.getAsJsonObject("data") : null;
            if (record == null) return;
            // 服务端备份记录形如 {type, updatedAt, data:{keeps,tracks}}，这里同时兼容旧格式。
            JsonObject data = record.has("data") && record.get("data").isJsonObject() ? record.getAsJsonObject("data") : record;
            if (replace) {
                AppDatabase.get().getKeepDao().deleteAll();
                com.github.catvod.utils.Prefers.put("update_tracks", "[]");
            }
            int keeps = 0;
            if (data.has("keeps") && data.get("keeps").isJsonArray()) {
                List<Keep> items = App.gson().fromJson(data.getAsJsonArray("keeps"), new com.google.gson.reflect.TypeToken<List<Keep>>() {
                }.getType());
                if (items != null) {
                    for (Keep item : items) {
                        if (item == null || TextUtils.isEmpty(item.getKey())) continue;
                        AppDatabase.get().getKeepDao().insertOrUpdate(item);
                        keeps++;
                    }
                }
            }
            int tracks = 0;
            JsonArray array = data.has("tracks") && data.get("tracks").isJsonArray() ? data.getAsJsonArray("tracks") : null;
            if (array != null) {
                for (JsonElement element : array) {
                    try {
                        UpdateTrack item = App.gson().fromJson(element, UpdateTrack.class);
                        if (item == null || TextUtils.isEmpty(item.getKey())) continue;
                        UpdateTrack.save(item);
                        tracks++;
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (keeps > 0 || tracks > 0 || replace) {
                CloudSyncStore.markKeepCount(keeps, tracks);
                RefreshEvent.keep();
                SpiderDebug.log("keep-backup", "restored keeps=%s tracks=%s", keeps, tracks);
            }
        } catch (Throwable e) {
            SpiderDebug.log("keep-backup", "restore failed: %s", e.toString());
        }
    }
}
