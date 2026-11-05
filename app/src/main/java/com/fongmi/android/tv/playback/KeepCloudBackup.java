package com.fongmi.android.tv.playback;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.AuthApi;
import com.fongmi.android.tv.api.config.VodConfig;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /** 默认按条目合并上传；`merge=false` 仅用于用户明确选择的「覆盖云端」。 */
    public static void backup(boolean notify) {
        backup(notify, true);
    }

    /**
     * 上传收藏/追更快照。
     * merge=true 时服务端按剧名+归一 key 求并集，并应用墓碑删除；
     * 快照同时携带本地删除墓碑（含原始剧名与 deletedAt），避免另一端把删掉的条目复活。
     */
    public static void backup(boolean notify, boolean merge) {
        if (!canBackup()) return;
        try {
            List<Keep> keeps = AppDatabase.get().getKeepDao().findAll();
            List<UpdateTrack> tracks = UpdateTrack.list();
            JsonObject data = new JsonObject();
            data.add("keeps", App.gson().toJsonTree(keeps));
            data.add("tracks", App.gson().toJsonTree(tracks));
            data.add("tombstones", KeepTombstoneStore.snapshot());
            // 上传计数也按剧名合并，避免同一部剧的多个站点被算成多条
            List<String> keepNames = new ArrayList<>();
            for (Keep keep : keeps) keepNames.add(keep.getVodName());
            final int keepCount = CloudSyncStore.distinctNames(keepNames);
            final int trackCount = tracks.size();
            AuthApi.backupSave(TYPE, data, merge, new AuthApi.Callback() {
                @Override
                public void success(JsonObject response) {
                    // 保存自己的 rev（merge 不递增；覆盖上传会递增），避免自己的写回被当成版本变化
                    CloudSyncStore.noteRev(response);
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
                    // rev 只记录，不参与全量重推判定（重推仅由 keep 备份 404 / 历史为空触发）
                    CloudSyncStore.noteRev(response);
                    mergeCloud(response);
                    backup(false);
                    App.post(() -> {
                        if (onDone != null) onDone.run();
                    });
                });
            }

            @Override
            public void error(String message) {
                // keep 备份不存在（服务端 404）→ 本地快照整份上传，并让本轮整份重推历史
                if (message != null && message.contains("暂无云端备份")) CloudSyncStore.requestFullResync();
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
            long cloudUpdatedAt = recordUpdatedAt(response);
            Map<String, Keep> keepMap = new HashMap<>();
            for (Keep keep : AppDatabase.get().getKeepDao().findAll()) {
                if (keep == null || TextUtils.isEmpty(keep.getKey())) continue;
                String identity = KeepTombstoneStore.identity(keep);
                Keep exists = keepMap.get(identity);
                // 同剧同名只保留一条：本地因换配置留下多段 key 时，保留更新时间最新的一条
                if (exists != null) {
                    Keep older = keep.getCreateTime() >= exists.getCreateTime() ? exists : keep;
                    Keep newer = keep.getCreateTime() >= exists.getCreateTime() ? keep : exists;
                    AppDatabase.get().getKeepDao().delete(older.getCid(), older.getKey());
                    keepMap.put(identity, newer);
                    continue;
                }
                keepMap.put(identity, keep);
            }
            Set<String> trackKeys = new HashSet<>();
            for (UpdateTrack track : UpdateTrack.list()) trackKeys.add(track.getKey());
            boolean changed = applyCloudTombstones(data, keepMap, trackKeys);
            if (data.has("keeps") && data.get("keeps").isJsonArray()) {
                List<Keep> items = App.gson().fromJson(data.getAsJsonArray("keeps"), new com.google.gson.reflect.TypeToken<List<Keep>>() {
                }.getType());
                if (items != null) {
                    for (Keep item : items) {
                        if (item == null || TextUtils.isEmpty(item.getKey())) continue;
                        String identity = KeepTombstoneStore.identity(item);
                        long tombstone = KeepTombstoneStore.deletedAt(identity);
                        long version = cloudUpdatedAt > 0 ? cloudUpdatedAt : item.getCreateTime();
                        // 墓碑不早于云端更新时间：说明本机已经删过这条，拒绝恢复（删除不复活）
                        if (tombstone > 0 && (version <= 0 || tombstone >= version)) continue;
                        Keep local = keepMap.get(identity);
                        if (local == null) {
                            // 跨端导入：cid 只在本机有意义，第三段统一换成本机配置
                            item.setCid(VodConfig.getCid());
                            item.setKey(localKey(item.getKey()));
                            AppDatabase.get().getKeepDao().insertOrUpdate(item);
                            keepMap.put(identity, item);
                            changed = true;
                        } else if (item.getCreateTime() > local.getCreateTime()) {
                            // 同一身份取更新时间最新的字段；key/cid 保持本地，进度以本地为准
                            local.setSiteName(item.getSiteName());
                            local.setVodName(item.getVodName());
                            local.setVodPic(item.getVodPic());
                            local.setType(item.getType());
                            local.setCreateTime(item.getCreateTime());
                            AppDatabase.get().getKeepDao().insertOrUpdate(local);
                            changed = true;
                        }
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

    /**
     * 把云端墓碑应用到本地：墓碑较新才删除，本地更新过的收藏/追更保留。
     * 服务端合并后不会再返回被删条目，这里处理另一端已删除、本机仍残留的情况。
     */
    private static boolean applyCloudTombstones(JsonObject data, Map<String, Keep> keepMap, Set<String> trackKeys) {
        Map<String, Long> tombstones = cloudTombstones(data);
        if (tombstones.isEmpty()) return false;
        boolean changed = false;
        for (String identity : new ArrayList<>(keepMap.keySet())) {
            Long at = tombstones.get(identity);
            if (at == null) continue;
            Keep local = keepMap.get(identity);
            if (local != null && local.getCreateTime() > at) continue;
            if (local != null) AppDatabase.get().getKeepDao().delete(local.getCid(), local.getKey());
            KeepTombstoneStore.mark(identity, local == null ? "" : local.getVodName(), at);
            keepMap.remove(identity);
            changed = true;
        }
        for (UpdateTrack track : UpdateTrack.list()) {
            String identity = KeepTombstoneStore.identity(track.getKey(), track.getVodName());
            Long at = tombstones.get(identity);
            if (at == null || track.getTime() > at) continue;
            UpdateTrack.remove(track.getKey());
            trackKeys.remove(track.getKey());
            KeepTombstoneStore.mark(identity, track.getVodName(), at);
            changed = true;
        }
        return changed;
    }

    /** 云端快照里的墓碑：tombstones 数组，同时兼容 keeps 中 deleted:true 的条目。 */
    private static Map<String, Long> cloudTombstones(JsonObject data) {
        Map<String, Long> map = new HashMap<>();
        collectTombstones(data.get("tombstones"), false, map);
        collectTombstones(data.get("keeps"), true, map);
        return map;
    }

    private static void collectTombstones(JsonElement element, boolean onlyDeleted, Map<String, Long> map) {
        if (element == null || !element.isJsonArray()) return;
        for (JsonElement child : element.getAsJsonArray()) {
            try {
                JsonObject item = child.getAsJsonObject();
                if (onlyDeleted && !(item.has("deleted") && item.get("deleted").getAsBoolean())) continue;
                String key = item.has("key") ? item.get("key").getAsString() : "";
                String name = item.has("vodName") ? item.get("vodName").getAsString() : "";
                if (TextUtils.isEmpty(key) && TextUtils.isEmpty(name)) continue;
                long at = item.has("deletedAt") ? item.get("deletedAt").getAsLong() : 0;
                String identity = KeepTombstoneStore.identity(key, name);
                map.merge(identity, at, Math::max);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 云端备份记录的更新时间，用来和本地删除墓碑比较；
     * 老格式没有该字段时返回 0，交由调用方回退到条目自身的 createTime。
     */
    private static long recordUpdatedAt(JsonObject response) {
        try {
            if (response == null || !response.has("data") || !response.get("data").isJsonObject()) return 0;
            JsonObject record = response.getAsJsonObject("data");
            return record.has("updatedAt") ? record.get("updatedAt").getAsLong() : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    /** 导入云端追更时把 key 第三段（cid）换成本机配置，cid 只在本机有意义。 */
    private static String localKey(String key) {
        String[] parts = key == null ? new String[0] : key.split(AppDatabase.SYMBOL);
        if (parts.length < 3) return key;
        return parts[0] + AppDatabase.SYMBOL + parts[1] + AppDatabase.SYMBOL + VodConfig.getCid();
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
                long cloudUpdatedAt = recordUpdatedAt(response);
                if (items != null) {
                    for (Keep item : items) {
                        if (item == null || TextUtils.isEmpty(item.getKey())) continue;
                        String identity = KeepTombstoneStore.identity(item);
                        long tombstone = KeepTombstoneStore.deletedAt(identity);
                        long version = cloudUpdatedAt > 0 ? cloudUpdatedAt : item.getCreateTime();
                        // 强制恢复也要尊重本地删除墓碑，否则「删掉的又被云端恢复」
                        if (tombstone > 0 && (version <= 0 || tombstone >= version)) continue;
                        item.setCid(VodConfig.getCid());
                        item.setKey(localKey(item.getKey()));
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
