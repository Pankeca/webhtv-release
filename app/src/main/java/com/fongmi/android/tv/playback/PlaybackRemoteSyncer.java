package com.fongmi.android.tv.playback;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.AccountStore;
import com.fongmi.android.tv.setting.CloudSyncStore;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.Response;

public final class PlaybackRemoteSyncer {

    // 服务端分区拉取在坏 blob 首次探测、冷 isolate 时可能超过 10s，60s 仅作客户端兜底
    private static final long TIMEOUT_MS = TimeUnit.SECONDS.toMillis(60);
    /** 每个同步源是否已完成「账号级分区」首次迁移，失败时不写，保留旧 cursor 重试。 */
    private static final String ACCOUNT_READY_PREFIX = "playback_account_partition_";
    /**
     * 删除保护开关：默认关闭。
     * 关闭时按墓碑时间取较新：远端墓碑比本地记录新才删除，本地更新过的记录保留。
     * 开启后把远端删除一律不应用（只做上传/合并/拉取新增），供极端情况下排查使用。
     */
    private static final String DELETE_APPLY_PROTECTED = "playback_delete_apply_protected";
    private static final Runnable PERIODIC = new Runnable() {
        @Override
        public void run() {
            Task.execute(() -> syncDue(false));
            App.post(this, TimeUnit.MINUTES.toMillis(5));
        }
    };
    private static boolean started;

    private PlaybackRemoteSyncer() {
    }

    public static void start() {
        if (started) return;
        started = true;
        App.post(PERIODIC, TimeUnit.SECONDS.toMillis(3));
        Task.execute(() -> syncDue(true));
    }

    public static void syncDue(boolean startup) {
        if (!ViewingRecordSyncStore.isEnabled() || Setting.isIncognito()) return;
        long now = System.currentTimeMillis();
        for (RemoteSyncConfig config : PlaybackRemoteSyncStore.list()) {
            if (!config.shouldSyncNow(now, startup)) continue;
            sync(config.id);
        }
    }

    public static PlaybackRemoteSyncResult sync(String id) {
        RemoteSyncConfig config = PlaybackRemoteSyncStore.find(id);
        PlaybackRemoteSyncResult result;
        if (config == null) {
            result = PlaybackRemoteSyncResult.failure("远端同步源不存在");
        } else {
            result = sync(config);
        }
        if (config != null) PlaybackRemoteSyncStore.markResult(config.id, result);
        return result;
    }

    private static PlaybackRemoteSyncResult sync(RemoteSyncConfig config) {
        try {
            if (!ViewingRecordSyncStore.isEnabled()) return PlaybackRemoteSyncResult.failure("观影记录同步未开启");
            if (Setting.isIncognito()) return PlaybackRemoteSyncResult.failure("隐身模式不允许同步");
            if (!config.isUsable()) return PlaybackRemoteSyncResult.failure("远端同步源未完成配置");
            String configKey = PlaybackConfigIdentity.currentKey();
            // 游标按账号隔离存储（网络分区 key 不变），切换账号不会复用别人的 since
            String cursorKey = PlaybackConfigIdentity.cursorScope(configKey);
            // 冻结规则 B：首次切到账号级分区时，先把本地历史整份 push 到新分区，
            // 成功后再用新 key 拉取；失败则保留旧 cursor，下次重试，绝不出现历史消失。
            if (!isAccountPartitionReady(config.id) && !migrateToAccountPartition(config)) {
                return PlaybackRemoteSyncResult.failure("账号分区迁移未完成，稍后重试");
            }
            String body = fetch(config, configKey, cursorKey);
            PlaybackRemoteSyncPayload payload = PlaybackRemoteSyncPayload.fromJson(body);
            boolean complete = config.maxItems <= 0 || payload.total() <= config.maxItems;
            payload.limit(config.maxItems);
            PlaybackProgressBatchResult batch = new PlaybackProgressBatchResult();
            if (isDeleteApplyProtected()) {
                SpiderDebug.log("playback-remote-sync", "delete-apply skipped by protection");
            } else {
                // Apply tombstones first; a newer upsert in the same response can still restore the item.
                batch.addAll(PlaybackProgressWriter.deleteFromRemoteSync(payload.deletions, config));
            }
            batch.addAll(PlaybackProgressWriter.applyFromRemoteSync(payload.upserts, config));
            RefreshEvent.history();
            SpiderDebug.log("playback-remote-sync", "source=%s fetched=%s applied=%s deleted=%s skipped=%s failed=%s", config.displayName(), batch.total, batch.applied, batch.deleted, batch.skipped, batch.failed);
            // Do not move the incremental cursor past malformed records; the server can
            // repair and resend them on the next run. A size-truncated page is likewise
            // intentionally replayed until every change has been handled.
            String nextSince = complete && batch.failed == 0 ? payload.nextSince : "";
            return PlaybackRemoteSyncResult.success(batch, cursorKey, nextSince);
        } catch (Throwable e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            SpiderDebug.log("playback-remote-sync", e);
            return PlaybackRemoteSyncResult.failure(message);
        }
    }

    /**
     * 首次切换到账号级分区：
     * 1）先整份 push 本地观看历史到新分区（没有可用的 push 出口时视为无需投递）；
     * 2）旧分区（按订阅地址派生）只做一次迁移读取，读到本机后就再也不读；
     * 3）任何一步失败都不写迁移标记，旧 cursor 保持原样，下次重试。
     */
    private static boolean migrateToAccountPartition(RemoteSyncConfig config) {
        try {
            List<PlaybackRecord> seeds = new ArrayList<>();
            for (History history : AppDatabase.get().getHistoryDao().findAll()) {
                if (!PlaybackRecord.canCreate(history)) continue;
                seeds.add(PlaybackRecord.from(history, null, "playback.progress", ""));
            }
            if (!new PlaybackWebhookSender().pushSeeds(seeds)) return false;
            for (String legacyKey : legacyCursorKeys(config)) {
                // 旧分区只映射到本机配置的才有迁移价值
                if (PlaybackConfigIdentity.cidForKey(legacyKey) == 0) continue;
                String body = fetch(config, legacyKey, legacyKey);
                PlaybackRemoteSyncPayload payload = PlaybackRemoteSyncPayload.fromJson(body);
                if (isDeleteApplyProtected()) {
                    SpiderDebug.log("playback-remote-sync", "delete-apply skipped by protection");
                } else {
                    PlaybackProgressWriter.deleteFromRemoteSync(payload.deletions, config);
                }
                PlaybackProgressWriter.applyFromRemoteSync(payload.upserts, config);
            }
            Prefers.put(ACCOUNT_READY_PREFIX + config.id, true);
            return true;
        } catch (Throwable e) {
            SpiderDebug.log("playback-remote-sync", e);
            return false;
        }
    }

    /**
     * 登录后整份投递本地观看历史到当前账号分区。
     * 未登录期间产生的记录只存在本地，合并与覆盖云端都必须先以本地快照作为种子，
     * 再由服务端按更新时间合并（覆盖云端不能删除云端独有条目，见交付说明的残留风险）。
     */
    public static boolean pushLocalHistory() {
        return pushLocalHistory(true);
    }

    /**
     * 启动同步用的增量投递：只补上次投递之后新增/变更的剧集。
     * `all=true` 用于云端为空或账号版本变化后的整份重传。
     */
    public static boolean pushLocalHistory(boolean all) {
        try {
            long uid = AccountStore.uid();
            long since = all ? 0 : CloudSyncStore.historyPushAt(uid);
            List<PlaybackRecord> seeds = new ArrayList<>();
            for (History history : AppDatabase.get().getHistoryDao().findAll()) {
                if (!PlaybackRecord.canCreate(history)) continue;
                // 播放/切集都会刷新 createTime，用它判断这条记录是否有新内容需要投递
                if (history.getCreateTime() <= since) continue;
                seeds.add(PlaybackRecord.from(history, null, "playback.progress", ""));
            }
            if (seeds.isEmpty()) return true;
            boolean ok = new PlaybackWebhookSender().pushSeeds(seeds);
            if (ok) CloudSyncStore.markHistoryPush(uid);
            return ok;
        } catch (Throwable e) {
            SpiderDebug.log("playback-remote-sync", e);
            return false;
        }
    }

    /** 云端被清空或账号版本变化：清掉账号分区游标，让下一次拉取从头开始。 */
    public static void resetAccountCursor() {
        try {
            RemoteSyncConfig config = PlaybackRemoteSyncStore.find(com.fongmi.android.tv.setting.AccountSync.SYNC_ID);
            if (config == null) return;
            config.clearCursor(PlaybackConfigIdentity.cursorScope(PlaybackConfigIdentity.currentKey()));
            PlaybackRemoteSyncStore.upsert(config);
            SpiderDebug.log("playback-remote-sync", "cursor reset for account partition");
        } catch (Throwable e) {
            SpiderDebug.log("playback-remote-sync", e);
        }
    }

    private static boolean isAccountPartitionReady(String id) {
        return Prefers.getBoolean(ACCOUNT_READY_PREFIX + id, false);
    }

    private static boolean isDeleteApplyProtected() {
        return Prefers.getBoolean(DELETE_APPLY_PROTECTED, false);
    }

    private static List<String> legacyCursorKeys(RemoteSyncConfig config) {
        List<String> keys = new ArrayList<>();
        if (config == null || config.cursors == null) return keys;
        for (String key : config.cursors.keySet()) {
            String normalized = PlaybackConfigIdentity.normalizeKey(key);
            // 账号分区自身的 cursor（含按账号隔离的 scoped key）不用迁移；空值与默认占位跳过
            if (TextUtils.isEmpty(normalized) || "_default".equals(normalized)) continue;
            if (PlaybackConfigIdentity.isAccountCursorKey(normalized)) continue;
            keys.add(normalized);
        }
        return keys;
    }

    private static String fetch(RemoteSyncConfig config, String configKey, String cursorKey) throws Exception {
        Request.Builder builder = new Request.Builder().url(config.url).get();
        builder.header("Accept", "application/json");
        PlaybackHttpHeaders.header(builder, "X-WebHTV-Config-Key", configKey);
        PlaybackHttpHeaders.header(builder, "X-WebHTV-Config-Name", PlaybackConfigIdentity.currentName());
        PlaybackHttpHeaders.header(builder, "X-WebHTV-Since", config.cursor(cursorKey));
        if (config.maxItems > 0) builder.header("X-WebHTV-Limit", String.valueOf(config.maxItems));
        if (!TextUtils.isEmpty(config.token)) builder.header("X-WebHTV-Token", config.token);
        try (Response response = OkHttp.client(TIMEOUT_MS).newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) throw new IllegalStateException("HTTP " + response.code());
            return response.body() == null ? "" : response.body().string();
        }
    }

    /**
     * 只读探测：当前账号分区在云端是否已有观影记录。
     * 用于登录时判断「新建账号（云端为空，直接上传本地）」还是「已有数据的老账号（弹窗选择合并/覆盖）」，
     * GET 不写任何数据，也不会推进本地 cursor。
     */
    public static boolean hasRemoteRecords() {
        return probeRemote().has;
    }

    /** 启动探测结果：`ok` 区分「确实为空」与「请求失败」，后者不能触发整份重传。 */
    public static final class RemoteProbe {

        public final boolean ok;
        public final boolean has;
        public final String epoch;

        RemoteProbe(boolean ok, boolean has, String epoch) {
            this.ok = ok;
            this.has = has;
            this.epoch = epoch == null ? "" : epoch;
        }
    }

    /**
     * 只读探测当前账号分区：云端是否已有记录 + 可选的服务端账号版本纪元。
     * GET 不写任何数据，也不会推进本地 cursor。
     */
    public static RemoteProbe probeRemote() {
        try {
            RemoteSyncConfig config = PlaybackRemoteSyncStore.find(com.fongmi.android.tv.setting.AccountSync.SYNC_ID);
            if (config == null || !config.isUsable()) return new RemoteProbe(false, false, "");
            Request.Builder builder = new Request.Builder().url(config.url).get();
            builder.header("Accept", "application/json");
            PlaybackHttpHeaders.header(builder, "X-WebHTV-Config-Key", PlaybackConfigIdentity.currentKey());
            PlaybackHttpHeaders.header(builder, "X-WebHTV-Config-Name", PlaybackConfigIdentity.currentName());
            // since=0 表示从头探测；limit=1 只取一条，判断「有没有」即可
            PlaybackHttpHeaders.header(builder, "X-WebHTV-Since", "0");
            builder.header("X-WebHTV-Limit", "1");
            if (!TextUtils.isEmpty(config.token)) builder.header("X-WebHTV-Token", config.token);
            try (Response response = OkHttp.client(TIMEOUT_MS).newCall(builder.build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) return new RemoteProbe(false, false, "");
                JsonObject json = JsonParser.parseString(response.body().string()).getAsJsonObject();
                boolean has = json.has("changes") && json.get("changes").isJsonArray() && json.getAsJsonArray("changes").size() > 0
                        || json.has("deleted") && json.get("deleted").isJsonArray() && json.getAsJsonArray("deleted").size() > 0;
                // 服务端未在增量响应里带版本号时为空，客户端退回「云端为空」+ backup 响应 rev 判定
                String epoch = "";
                for (String name : new String[]{"rev", "epoch"}) {
                    if (!json.has(name) || json.get(name).isJsonNull()) continue;
                    try {
                        epoch = json.get(name).getAsString();
                        break;
                    } catch (Throwable ignored) {
                    }
                }
                return new RemoteProbe(true, has, epoch);
            }
        } catch (Throwable e) {
            return new RemoteProbe(false, false, "");
        }
    }
}
