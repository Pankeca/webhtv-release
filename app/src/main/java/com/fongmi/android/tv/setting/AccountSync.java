package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.AuthApi;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.playback.KeepCloudBackup;
import com.fongmi.android.tv.playback.PlaybackRemoteSyncStore;
import com.fongmi.android.tv.playback.PlaybackRemoteSyncer;
import com.fongmi.android.tv.playback.PlaybackWebhookStore;
import com.fongmi.android.tv.playback.RemoteSyncConfig;
import com.fongmi.android.tv.playback.WebhookConfig;
import com.fongmi.android.tv.utils.Task;

/**
 * Keeps the account-bound cloud sync sources bound to the signed-in user.
 * Login binds automatically; the personal center only edits backup switches.
 */
public final class AccountSync {

    public static final String SYNC_ID = "cloud-sync";
    public static final String HOOK_ID = "cloud-webhook";

    private AccountSync() {
    }

    public static void onLogin() {
        CloudSyncStore.applyLoginDefaults();
        bind();
        // 登录后先不自动上传，个人中心会提示是否同步本地记录到云端；本地为空则静默拉取。
        KeepCloudBackup.restoreIfEmpty();
    }

    /** Re-binds silently on startup so a stale token or missing config heals itself. */
    public static void bindIfNeeded() {
        if (!AccountStore.isLogin()) return;
        bind();
    }

    public static void bind() {
        String server = AuthApi.server();
        if (server.isEmpty() || !AccountStore.isLogin()) return;
        boolean enabled = CloudSyncStore.isHistoryEnabled();
        RemoteSyncConfig previous = PlaybackRemoteSyncStore.find(SYNC_ID);
        RemoteSyncConfig config = new RemoteSyncConfig();
        config.id = SYNC_ID;
        config.name = name();
        config.enabled = enabled;
        config.url = server + "/api/playback/sync";
        config.token = AccountStore.token();
        config.syncOnStartup = true;
        config.intervalMinutes = 30;
        config.maxItems = 100;
        if (previous != null) {
            config.cursors = previous.cursors;
            config.lastSyncAt = previous.lastSyncAt;
            config.lastSuccessAt = previous.lastSuccessAt;
        }
        PlaybackRemoteSyncStore.upsert(config);
        WebhookConfig hook = new WebhookConfig();
        hook.id = HOOK_ID;
        hook.name = name();
        hook.enabled = enabled;
        hook.suspended = false;
        hook.url = server + "/api/playback/events";
        hook.token = AccountStore.token();
        hook.progressIntervalSec = 120;
        hook.maxRetries = 2;
        PlaybackWebhookStore.upsert(hook);
        KeepCloudBackup.schedule(5000);
    }

    public static void unbind() {
        PlaybackRemoteSyncStore.remove(SYNC_ID);
        PlaybackWebhookStore.remove(HOOK_ID);
    }

    public static void setHistoryBackup(boolean enabled) {
        CloudSyncStore.setHistoryEnabled(enabled);
        if (enabled) {
            bind();
            if (AccountStore.isLogin()) Task.execute(() -> PlaybackRemoteSyncer.sync(SYNC_ID));
        } else {
            setSourceEnabled(false);
        }
    }

    public static void setKeepBackup(boolean enabled) {
        CloudSyncStore.setKeepEnabled(enabled);
        if (enabled) KeepCloudBackup.schedule(600);
    }

    public static void syncNow(Runnable onDone) {
        bindIfNeeded();
        Task.execute(() -> {
            if (CloudSyncStore.isHistoryEnabled()) PlaybackRemoteSyncer.sync(SYNC_ID);
            if (CloudSyncStore.isKeepEnabled()) KeepCloudBackup.backup(false);
            CloudConfigSync.markHistoryCount(localHistoryCount());
            Runnable finish = () -> App.post(() -> {
                if (onDone != null) onDone.run();
            });
            // 配置备份是异步网络请求，需等它回写云端数量后再通知刷新，否则统计会滞后
            if (CloudSyncStore.isConfigEnabled()) {
                CloudConfigSync.backup(true, true, true, new CloudConfigSync.Callback() {
                    @Override
                    public void success(String message) {
                        finish.run();
                    }

                    @Override
                    public void error(String message) {
                        finish.run();
                    }
                });
            } else {
                finish.run();
            }
        });
    }

    /**
     * 登录后按用户选择处理本地记录与云端记录：
     * MODE_CLOUD_FIRST 云端覆盖本地，MODE_LOCAL_FIRST 本地覆盖云端，MODE_MERGE 双向增量。
     */
    public static void applyLoginChoice(int mode, Runnable onDone) {
        CloudConfigSync.Callback done = new CloudConfigSync.Callback() {
            @Override
            public void success(String message) {
                syncHistoryAndFinish(onDone);
            }

            @Override
            public void error(String message) {
                syncHistoryAndFinish(onDone);
            }
        };
        if (mode == CloudSyncStore.MODE_CLOUD_FIRST) {
            KeepCloudBackup.restore(true, () -> CloudConfigSync.pullPersonal(true, true, true, CloudSyncStore.MODE_CLOUD_FIRST, done));
            return;
        }
        if (mode == CloudSyncStore.MODE_LOCAL_FIRST) {
            Task.execute(() -> {
                if (CloudSyncStore.isKeepEnabled()) KeepCloudBackup.backup(false);
                CloudConfigSync.backup(true, true, true, done);
            });
            return;
        }
        CloudConfigSync.pullPersonal(true, true, true, CloudSyncStore.MODE_MERGE, done);
    }

    private static void syncHistoryAndFinish(Runnable onDone) {
        Task.execute(() -> {
            if (CloudSyncStore.isHistoryEnabled()) PlaybackRemoteSyncer.sync(SYNC_ID);
            finish(onDone);
        });
    }

    private static void finish(Runnable onDone) {
        CloudConfigSync.markHistoryCount(localHistoryCount());
        App.post(() -> {
            if (onDone != null) onDone.run();
        });
    }

    public static int localHistoryCount() {
        try {
            // 与「最近观看」页面完全一致：当前配置、保留期内的记录
            return com.fongmi.android.tv.bean.History.get().size();
        } catch (Throwable e) {
            return 0;
        }
    }

    private static void setSourceEnabled(boolean enabled) {
        RemoteSyncConfig config = PlaybackRemoteSyncStore.find(SYNC_ID);
        if (config != null) {
            config.enabled = enabled;
            PlaybackRemoteSyncStore.upsert(config);
        }
        WebhookConfig hook = findHook();
        if (hook != null) {
            hook.enabled = enabled;
            hook.suspended = false;
            PlaybackWebhookStore.upsert(hook);
        }
    }

    private static WebhookConfig findHook() {
        for (WebhookConfig hook : PlaybackWebhookStore.list()) if (HOOK_ID.equals(hook.id)) return hook;
        return null;
    }

    public static int activeSources() {
        return PlaybackRemoteSyncStore.activeCount();
    }

    public static int totalSources() {
        return PlaybackRemoteSyncStore.totalCount();
    }

    private static String name() {
        return App.get().getString(R.string.account_sync_source_name);
    }
}
