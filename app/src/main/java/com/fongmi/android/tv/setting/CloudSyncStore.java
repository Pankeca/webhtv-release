package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

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
    private static final String KEY_SYNC_MODE = "cloud_sync_mode";

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

    /** 本地/云端双向同步方式：默认增量双向，可选本地或云端单向覆盖。 */
    public static int syncMode() {
        return Prefers.getInt(KEY_SYNC_MODE, MODE_MERGE);
    }

    public static void setSyncMode(int mode) {
        Prefers.put(KEY_SYNC_MODE, mode);
    }
}
