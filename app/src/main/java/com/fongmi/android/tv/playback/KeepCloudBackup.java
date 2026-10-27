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

import java.util.List;

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
            JsonObject data = new JsonObject();
            data.add("keeps", App.gson().toJsonTree(AppDatabase.get().getKeepDao().findAll()));
            data.add("tracks", App.gson().toJsonTree(UpdateTrack.list()));
            AuthApi.backupSave(TYPE, data, new AuthApi.Callback() {
                @Override
                public void success(JsonObject response) {
                    CloudSyncStore.markKeepBackup();
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
        if (!AccountStore.isLogin() || !CloudSyncStore.isKeepEnabled()) return;
        Task.execute(() -> {
            try {
                if (!AppDatabase.get().getKeepDao().findAll().isEmpty()) return;
                if (!UpdateTrack.list().isEmpty()) return;
            } catch (Throwable e) {
                return;
            }
            AuthApi.backupLoad(TYPE, new AuthApi.Callback() {
                @Override
                public void success(JsonObject response) {
                    Task.execute(() -> apply(response));
                }

                @Override
                public void error(String message) {
                    SpiderDebug.log("keep-backup", "restore skipped: %s", message);
                }
            });
        });
    }

    private static void apply(JsonObject response) {
        try {
            JsonObject data = response.has("data") && response.get("data").isJsonObject() ? response.getAsJsonObject("data") : null;
            if (data == null) return;
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
            if (keeps > 0 || tracks > 0) {
                CloudSyncStore.markKeepBackup();
                RefreshEvent.keep();
                SpiderDebug.log("keep-backup", "restored keeps=%s tracks=%s", keeps, tracks);
            }
        } catch (Throwable e) {
            SpiderDebug.log("keep-backup", "restore failed: %s", e.toString());
        }
    }
}
