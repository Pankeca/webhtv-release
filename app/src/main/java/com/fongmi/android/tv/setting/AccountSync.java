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
import com.google.gson.JsonObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the account-bound cloud sync sources bound to the signed-in user.
 * Login binds automatically; the personal center only edits backup switches.
 */
public final class AccountSync {

    /** 登录探测回调：云端是否已有该账号的数据。 */
    public interface CloudProbeCallback {
        void onDone(boolean hasCloudData);
    }

    public static final String SYNC_ID = "cloud-sync";
    public static final String HOOK_ID = "cloud-webhook";

    private AccountSync() {
    }

    public static void onLogin() {
        CloudSyncStore.applyLoginDefaults();
        bind();
        // 登录后先不自动上传，个人中心按云端状态决定：空账号直接上传，老账号弹窗选合并/覆盖。
        KeepCloudBackup.restoreIfEmpty();
    }

    /**
     * 只读探测当前账号云端是否已有数据（观影记录 / 追更 / 点播源 / 直播源）。
     * 判定口径与 docs/P0账号级数据合并规格.md 一致：任意一类有数据即视为「已有数据的老账号」。
     */
    public static void probeCloudData(CloudProbeCallback callback) {
        if (!AccountStore.isLogin()) {
            callback.onDone(false);
            return;
        }
        Task.execute(() -> {
            boolean has = PlaybackRemoteSyncer.hasRemoteRecords();
            if (!has) has = backupHasData("keep");
            if (!has) has = backupHasData("config");
            boolean result = has;
            App.post(() -> callback.onDone(result));
        });
    }

    private static boolean backupHasData(String type) {
        final boolean[] has = {false};
        final CountDownLatch latch = new CountDownLatch(1);
        AuthApi.backupLoad(type, new AuthApi.Callback() {
            @Override
            public void success(JsonObject response) {
                has[0] = hasEntries(response);
                latch.countDown();
            }

            @Override
            public void error(String message) {
                latch.countDown();
            }
        });
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return has[0];
    }

    private static boolean hasEntries(JsonObject response) {
        if (response == null) return false;
        JsonObject data = response.has("data") && response.get("data").isJsonObject() ? response.getAsJsonObject("data") : response;
        if (data.has("data") && data.get("data").isJsonObject()) data = data.getAsJsonObject("data");
        for (String key : new String[]{"keeps", "tracks", "vod", "live"}) {
            if (data.has(key) && data.get(key).isJsonArray() && data.getAsJsonArray(key).size() > 0) return true;
        }
        return false;
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
        // 冻结规则 C：只读合并对方 tvbox_config 里的点播/直播地址（按 URL 补缺，不覆盖本地）
        TvboxConfigSync.schedule();
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

    /** 手动/防抖同步：固定双向合并，不再读取旧的「同步方式」偏好。 */
    public static void syncNow(Runnable onDone) {
        sync(CloudSyncStore.MODE_MERGE, onDone);
    }

    /**
     * 启动 / 回前台同步：登录态才执行「上传本地 → 拉取云端增量 → 合并」，未登录直接返回。
     * 云端为空或服务端账号版本纪元变化时，先清掉账号分区游标并整份重传，
     * 避免清空前的旧游标让新增记录永远拉不到。
     */
    public static void syncStartup(Runnable onDone) {
        if (!AccountStore.isLogin() || AuthApi.server().isEmpty()) {
            finish(onDone);
            return;
        }
        bindIfNeeded();
        Task.execute(() -> {
            boolean full = needFullResync();
            if (full) PlaybackRemoteSyncer.resetAccountCursor();
            mergeBothWays(full, onDone);
        });
    }

    /**
     * 全量重传判定只保留两个条件：
     * 1）keep 备份不存在（服务端 backupLoad 404，由 KeepCloudBackup.merge 置位）；
     * 2）云端历史分区为空（探测成功且 0 条记录）。
     * rev/epoch 仅记录，不参与判定；探测失败按「未知」处理，不触发重传。
     */
    private static boolean needFullResync() {
        PlaybackRemoteSyncer.RemoteProbe probe = PlaybackRemoteSyncer.probeRemote();
        if (!probe.ok) return false;
        return !probe.has;
    }

    /**
     * 本地与云端保持一致的统一入口：
     * MODE_MERGE 双向增量（本地同 key 优先，补齐云端独有记录）；MODE_LOCAL_FIRST 本地覆盖云端；
     * MODE_CLOUD_FIRST 云端覆盖本地。
     */
    public static void sync(int mode, Runnable onDone) {
        bindIfNeeded();
        if (mode == CloudSyncStore.MODE_CLOUD_FIRST) {
            restore(CloudSyncStore.MODE_CLOUD_FIRST, onDone);
            return;
        }
        if (mode == CloudSyncStore.MODE_LOCAL_FIRST) {
            upload(CloudSyncStore.MODE_LOCAL_FIRST, onDone);
            return;
        }
        mergeBothWays(false, onDone);
    }

    /**
     * 双向合并：先投递本地历史（`full` 为真时整份），再按条目拉取并合并追更/配置，
     * 最后拉取账号分区增量历史。任一环节失败都不影响其余环节。
     */
    private static void mergeBothWays(boolean full, Runnable onDone) {
        Task.execute(() -> {
            PlaybackRemoteSyncer.pushLocalHistory(full);
            KeepCloudBackup.merge(() -> Task.execute(() -> {
                // 合并时发现服务端 rev 变化（清空/覆盖/删号）→ 清游标 + 整份重推本地历史
                if (CloudSyncStore.consumeFullResync()) {
                    PlaybackRemoteSyncer.resetAccountCursor();
                    PlaybackRemoteSyncer.pushLocalHistory(true);
                }
                if (CloudSyncStore.isHistoryEnabled()) PlaybackRemoteSyncer.sync(SYNC_ID);
                if (!CloudSyncStore.isConfigEnabled()) {
                    finish(onDone);
                    return;
                }
                CloudConfigSync.backupMerge(true, true, true, new CloudConfigSync.Callback() {
                    @Override
                    public void success(String message) {
                        pullConfigMerge(onDone);
                    }

                    @Override
                    public void error(String message) {
                        finish(onDone);
                    }
                });
            }));
        });
    }

    /** 增量模式上传并集后再拉一次，把云端独有的配置补到本地。 */
    private static void pullConfigMerge(Runnable onDone) {
        CloudConfigSync.pullPersonal(true, true, true, CloudSyncStore.MODE_MERGE, new CloudConfigSync.Callback() {
            @Override
            public void success(String message) {
                finish(onDone);
            }

            @Override
            public void error(String message) {
                finish(onDone);
            }
        });
    }

    /**
     * 主动上传到云端：
     * MODE_LOCAL_FIRST 用本地数据覆盖云端同名记录；MODE_MERGE 只把本地新增内容与云端增量合并。
     */
    public static void upload(int mode, Runnable onDone) {
        bindIfNeeded();
        Task.execute(() -> {
            if (CloudSyncStore.isHistoryEnabled()) PlaybackRemoteSyncer.sync(SYNC_ID);
            // 追更/收藏改为「先拉云端再按条目合并」，避免整包覆盖把另一端的条目盖掉；
            // 只有用户明确选择「覆盖云端」时才整包上传。
            CloudConfigSync.markHistoryCount(localHistoryCount());
            Runnable finish = () -> App.post(() -> {
                if (onDone != null) onDone.run();
            });
            CloudConfigSync.Callback callback = new CloudConfigSync.Callback() {
                @Override
                public void success(String message) {
                    finish.run();
                }

                @Override
                public void error(String message) {
                    finish.run();
                }
            };
            Runnable config = () -> {
                if (!CloudSyncStore.isConfigEnabled()) {
                    finish.run();
                    return;
                }
                // 配置备份是异步网络请求，需等它回写云端数量后再通知刷新，否则统计会滞后
                if (mode == CloudSyncStore.MODE_MERGE) CloudConfigSync.backupMerge(true, true, true, callback);
                else CloudConfigSync.backup(true, true, true, callback);
            };
            if (!CloudSyncStore.isKeepEnabled()) {
                config.run();
                return;
            }
            if (mode == CloudSyncStore.MODE_MERGE) KeepCloudBackup.merge(config);
            else {
                // 用户明确选择「覆盖云端」：整包覆盖，不走服务端并集
                KeepCloudBackup.backup(false, false);
                config.run();
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
                // 覆盖云端：先把本地观看历史整份作为种子投递，再整份上传追更/源
                PlaybackRemoteSyncer.pushLocalHistory();
                if (CloudSyncStore.isKeepEnabled()) KeepCloudBackup.backup(false, false);
                CloudConfigSync.backup(true, true, true, done);
            });
            return;
        }
        Task.execute(() -> {
            // 合并：本地未登录期间的记录也要先投递，再由服务端按更新时间合并
            PlaybackRemoteSyncer.pushLocalHistory();
            // 追更/收藏按剧名+归一 key 取并集，再拉取配置合并
            KeepCloudBackup.merge(() -> Task.execute(() -> CloudConfigSync.pullPersonal(true, true, true, CloudSyncStore.MODE_MERGE, done)));
        });
    }

    /**
     * 从云端恢复到本地：覆盖模式先清空并回灌云端追更/配置，再合并历史；
     * 增量模式只补齐本地缺失的记录，保留本地较新的数据。
     */
    public static void restore(int mode, Runnable onDone) {
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
        KeepCloudBackup.restore(mode == CloudSyncStore.MODE_CLOUD_FIRST, () -> CloudConfigSync.pullPersonal(true, true, true, mode, done));
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
