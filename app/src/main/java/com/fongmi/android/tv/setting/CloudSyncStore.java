package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cloud backup preferences for the personal center. These flags only control
 * the account-bound cloud backup; manually configured sync sources keep their
 * own settings untouched.
 */
public final class CloudSyncStore {

    private static final String KEY_KEEP = "cloud_backup_keep";
    private static final String KEY_HISTORY = "cloud_backup_history";
    private static final String KEY_CONFIG = "cloud_backup_config";
    private static final String KEY_KEEP_AT = "cloud_backup_keep_at";
    private static final String KEY_HISTORY_AT = "cloud_backup_history_at";
    private static final String KEY_KEEP_COUNT = "cloud_backup_keep_count";
    private static final String KEY_HISTORY_COUNT = "cloud_backup_history_count";
    private static final String KEY_CONFIG_COUNT = "cloud_backup_config_count";
    private static final String KEY_CONFLICT_MODE = "cloud_conflict_mode";
    private static final String KEY_CONFLICT_REMEMBER = "cloud_conflict_remember";
    /** 账号级版本纪元：服务端清空/删除时递增，客户端发现变化即全量重同步。 */
    private static final String KEY_EPOCH_PREFIX = "cloud_account_epoch_";
    /** 本地观看历史最后一次整份投递的时间，用于启动时只补新增/变更的剧集。 */
    private static final String KEY_HISTORY_PUSH_PREFIX = "cloud_history_push_at_";

    /** 冲突处理：增量合并 / 云端覆盖本地 / 本地覆盖云端。 */
    public static final int MODE_MERGE = 0;
    public static final int MODE_CLOUD_FIRST = 1;
    public static final int MODE_LOCAL_FIRST = 2;

    private CloudSyncStore() {
    }

    /** First login defaults both backup switches to on. */
    public static void applyLoginDefaults() {
        if (!Prefers.getPrefers().contains(KEY_KEEP)) Prefers.put(KEY_KEEP, true);
        if (!Prefers.getPrefers().contains(KEY_HISTORY)) Prefers.put(KEY_HISTORY, true);
    }

    public static boolean isKeepEnabled() {
        return Prefers.getBoolean(KEY_KEEP, true);
    }

    public static void setKeepEnabled(boolean enabled) {
        Prefers.put(KEY_KEEP, enabled);
    }

    public static boolean isHistoryEnabled() {
        return Prefers.getBoolean(KEY_HISTORY, true);
    }

    public static void setHistoryEnabled(boolean enabled) {
        Prefers.put(KEY_HISTORY, enabled);
    }

    public static boolean isConfigEnabled() {
        return Prefers.getBoolean(KEY_CONFIG, true);
    }

    public static void setConfigEnabled(boolean enabled) {
        Prefers.put(KEY_CONFIG, enabled);
    }

    public static long keepBackupAt() {
        return Prefers.getLong(KEY_KEEP_AT);
    }

    public static void markKeepBackup() {
        Prefers.put(KEY_KEEP_AT, System.currentTimeMillis());
    }

    public static long historyBackupAt() {
        return Prefers.getLong(KEY_HISTORY_AT);
    }

    public static void markHistoryBackup() {
        Prefers.put(KEY_HISTORY_AT, System.currentTimeMillis());
    }

    /** 记录最近一次与云端同步后的云端数量，用于个人中心统计展示。 */
    public static void markKeepCount(int keeps, int tracks) {
        Prefers.put(KEY_KEEP_COUNT, keeps);
        Prefers.put(KEY_KEEP_AT, System.currentTimeMillis());
        Prefers.put("cloud_backup_track_count", tracks);
    }

    public static int cloudKeepCount() {
        return Prefers.getInt(KEY_KEEP_COUNT);
    }

    /**
     * 追更/收藏的展示口径：同一剧名在多站点、多配置下只算一条。
     * 个人中心「本地 N · 云端 N」以及云端计数都使用这个口径。
     */
    public static int distinctNames(Collection<String> names) {
        if (names == null) return 0;
        Set<String> unique = new HashSet<>();
        for (String name : names) {
            if (name == null) continue;
            String value = name.trim();
            if (!value.isEmpty()) unique.add(value);
        }
        return unique.size();
    }

    public static int cloudTrackCount() {
        return Prefers.getInt("cloud_backup_track_count");
    }

    public static void markHistoryCount(int count) {
        Prefers.put(KEY_HISTORY_COUNT, count);
        Prefers.put(KEY_HISTORY_AT, System.currentTimeMillis());
    }

    public static int cloudHistoryCount() {
        return Prefers.getInt(KEY_HISTORY_COUNT);
    }

    public static void markConfigCount(int vod, int live) {
        Prefers.put(KEY_CONFIG_COUNT, vod + live);
        Prefers.put("cloud_backup_vod_count", vod);
        Prefers.put("cloud_backup_live_count", live);
        Prefers.put("cloud_backup_config_at", System.currentTimeMillis());
    }

    public static int cloudVodCount() {
        return Prefers.getInt("cloud_backup_vod_count");
    }

    public static int cloudLiveCount() {
        return Prefers.getInt("cloud_backup_live_count");
    }

    /** 云端已备份的公共配置项总数（点播源 + 直播源），个人中心「配置备份」行使用。 */
    public static int cloudConfigCount() {
        return Prefers.getInt(KEY_CONFIG_COUNT);
    }

    public static long configBackupAt() {
        return Prefers.getLong("cloud_backup_config_at");
    }

    public static int conflictMode() {
        return Prefers.getInt(KEY_CONFLICT_MODE, MODE_MERGE);
    }

    public static void setConflictMode(int mode) {
        Prefers.put(KEY_CONFLICT_MODE, mode);
    }

    public static boolean isConflictRemembered() {
        return Prefers.getBoolean(KEY_CONFLICT_REMEMBER, false);
    }

    public static void setConflictRemembered(boolean remember) {
        Prefers.put(KEY_CONFLICT_REMEMBER, remember);
    }

    /** 服务端下发的账号级版本纪元；服务端未提供时为空，调用方退回「云端为空」判定。 */
    public static String cloudEpoch(long uid) {
        return uid <= 0 ? "" : Prefers.getString(KEY_EPOCH_PREFIX + uid, "");
    }

    public static void setCloudEpoch(long uid, String epoch) {
        if (uid <= 0 || epoch == null || epoch.trim().isEmpty()) return;
        Prefers.put(KEY_EPOCH_PREFIX + uid, epoch.trim());
    }

    public static String epochKey(long uid) {
        return KEY_EPOCH_PREFIX + uid;
    }

    /** 上次本地历史整份投递的时间；0 表示尚未投递，需要整份上传。 */
    public static long historyPushAt(long uid) {
        return uid <= 0 ? 0 : Prefers.getLong(KEY_HISTORY_PUSH_PREFIX + uid, 0);
    }

    public static void markHistoryPush(long uid) {
        if (uid <= 0) return;
        Prefers.put(KEY_HISTORY_PUSH_PREFIX + uid, System.currentTimeMillis());
    }

    /** keep 备份 404（已被清空/删除）时置位，由本轮合并消费后整份重推。 */
    private static final AtomicBoolean FULL_RESYNC = new AtomicBoolean(false);

    public static void requestFullResync() {
        FULL_RESYNC.set(true);
    }

    public static boolean consumeFullResync() {
        return FULL_RESYNC.getAndSet(false);
    }

    /**
     * 读取服务端响应里的账号版本号 rev 并落盘，返回 -1 表示响应未带 rev。
     * rev 由服务端在整份覆盖、清空账号、删除账号时递增。
     */
    public static long noteRev(JsonObject response) {
        JsonObject source = revSource(response);
        if (source == null) return -1;
        try {
            long rev = source.get("rev").getAsLong();
            if (rev > 0) setCloudEpoch(AccountStore.uid(), String.valueOf(rev));
            return rev;
        } catch (Throwable e) {
            return -1;
        }
    }

    private static JsonObject revSource(JsonObject response) {
        if (response == null) return null;
        if (response.has("rev") && !response.get("rev").isJsonNull()) return response;
        if (response.has("data") && response.get("data").isJsonObject()) {
            JsonObject data = response.getAsJsonObject("data");
            if (data.has("rev") && !data.get("rev").isJsonNull()) return data;
        }
        return null;
    }
}
