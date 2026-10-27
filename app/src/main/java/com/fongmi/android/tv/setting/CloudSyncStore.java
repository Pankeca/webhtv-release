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
    private static final String KEY_KEEP_AT = "cloud_backup_keep_at";
    private static final String KEY_HISTORY_AT = "cloud_backup_history_at";

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
}
