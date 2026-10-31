package com.fongmi.android.tv.setting;

import android.content.SharedPreferences;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.AuthApi;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Prefers;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「改完别管，过一会儿自己同步」。
 *
 * <p>为什么要单独一层——个人中心里原来只有几个按钮能触发同步，用户看了几集、加了几个追更、
 * 改了点设置，云端都还是旧数据，换台设备登录等于白看。这里在每个数据变更点打一个点，
 * 攒够一小段时间没有新改动才真正上传，避免「播一集上传十几次」。
 *
 * <p>三条触发线：
 * <ol>
 *   <li><b>变更后防抖</b>：任何数据变更 {@link #onDataChanged(String)} 都只是记一笔，
 *       距离最后一次改动静默 {@link #DEBOUNCE_MS} 之后才同步；</li>
 *   <li><b>启动</b>：已登录且距离上次同步超过 {@link #STARTUP_MIN_GAP_MS} 才补一次，
 *       否则每次冷启动都白跑一遍接口；</li>
 *   <li><b>退到后台</b>：给 {@link #BACKGROUND_DELAY_MS} 的缓冲，等播放暂停、进度落库之后再收敛。</li>
 * </ol>
 *
 * <p>同步过程中产生的写入会回调 {@link #onDataChanged(String)}，用 {@link #syncing} 挡住，
 * 否则同步→写库→再同步会自己转圈。
 */
public final class SyncScheduler {

    private static final String TAG = "sync-scheduler";
    private static final String KEY_LAST_AT = "sync_scheduler_last_at";
    private static final long DEBOUNCE_MS = 8_000;
    private static final long STARTUP_MIN_GAP_MS = 60_000;
    private static final long BACKGROUND_DELAY_MS = 30_000;

    private static final AtomicBoolean syncing = new AtomicBoolean(false);
    private static final Runnable DEBOUNCED = SyncScheduler::run;

    private static volatile long dueAt;
    private static volatile boolean posted;
    private static volatile boolean prefListenerBound;

    private SyncScheduler() {
    }

    /** 任意数据变更（历史、追更、追更改动、设置项）都调这里。 */
    public static void onDataChanged(String reason) {
        // 同步过程自己也会写库（合并云端记录），不挡掉会同步→写库→再同步地转圈
        if (syncing.get()) return;
        SpiderDebug.log(TAG, "data changed: %s", reason);
        schedule(DEBOUNCE_MS);
    }

    public static void onStartup() {
        long last = Prefers.getLong(KEY_LAST_AT);
        if (System.currentTimeMillis() - last < STARTUP_MIN_GAP_MS) return;
        schedule(3_000);
    }

    public static void onBackground() {
        schedule(BACKGROUND_DELAY_MS);
    }

    /** 设置项一旦被改就跟着同步：Config（点播/直播源）和设置本身的备份都靠这一层触发。 */
    public static void bindPreferenceListener() {
        if (prefListenerBound) return;
        try {
            Prefers.getPrefers().registerOnSharedPreferenceChangeListener(LISTENER);
            prefListenerBound = true;
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "bind preference listener failed: %s", e);
        }
    }

    private static final SharedPreferences.OnSharedPreferenceChangeListener LISTENER = (prefs, key) -> {
        if (key == null || KEY_LAST_AT.equals(key)) return;
        // 播放进度、弹幕偏好这种高频项也会走到这里，挡掉明显和账号备份无关的键
        if (key.startsWith("update_pending") || key.startsWith("sync_scheduler")) return;
        onDataChanged("pref:" + key);
    };

    private static boolean canSync() {
        return AccountStore.isLogin() && !AuthApi.server().isEmpty();
    }

    private static void schedule(long delayMs) {
        if (!canSync()) return;
        dueAt = System.currentTimeMillis() + delayMs;
        if (posted) return;
        posted = true;
        App.post(DEBOUNCED, delayMs);
    }

    private static void run() {
        long wait = dueAt - System.currentTimeMillis();
        if (wait > 250) {
            // 防抖窗口内又来了新改动，往后顺延，但不再重开一个新的排队项
            App.post(DEBOUNCED, wait);
            return;
        }
        posted = false;
        if (!canSync()) return;
        if (!syncing.compareAndSet(false, true)) return;
        try {
            AccountSync.syncNow(() -> {
                syncing.set(false);
                Prefers.put(KEY_LAST_AT, System.currentTimeMillis());
                SpiderDebug.log(TAG, "synced");
            });
        } catch (Throwable e) {
            syncing.set(false);
            SpiderDebug.log(TAG, "sync failed: %s", e.toString());
        }
    }
}
