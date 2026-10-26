package com.fongmi.android.tv.download;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;

import java.util.List;

/**
 * 下载前台服务：保证退到后台/锁屏后继续下载，并在通知栏显示总进度、速度与暂停入口。
 * targetSdk 为 28，通知权限无需运行时申请。
 */
public class DownloadService extends Service implements DownloadTask.Listener {

    private static final String CHANNEL = "download";
    private static final int NOTIFY_ID = 9528;
    // 空闲通知必须用独立 id：否则 stopForeground 移除的正是同 id 通知，完成/暂停提示会一起消失
    private static final int NOTIFY_IDLE_ID = 9529;
    private static final String ACTION_PAUSE = BuildConfig.APPLICATION_ID + ".download.PAUSE";
    private static final long MIN_INTERVAL = 800L;

    private static volatile boolean running;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private long lastNotify;
    private boolean lastBusy;

    public static void start(Context context) {
        if (running) return;
        try {
            ContextCompat.startForegroundService(context, new Intent(context, DownloadService.class));
        } catch (Throwable e) {
            Log.e("DownloadService", "start failed", e);
        }
    }

    public static void stop(Context context) {
        if (!running) return;
        // 先复位标志：stopService 到 onDestroy 之间新任务到来时仍能重新拉起前台服务
        running = false;
        try {
            context.stopService(new Intent(context, DownloadService.class));
        } catch (Throwable ignored) {
        }
    }

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
        acquireLocks();
        DownloadManager.get().addListener(this);
        clearIdle();
        startForegroundCompat(notification(DownloadManager.get().tasks()));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_PAUSE.equals(intent.getAction())) {
            DownloadManager.get().pause(DownloadManager.get().tasks());
            return START_NOT_STICKY;
        }
        update(DownloadManager.get().tasks());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        DownloadManager.get().removeListener(this);
        releaseLocks();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onChange(List<DownloadTask> tasks) {
        boolean busy = DownloadManager.get().hasBusy();
        long now = System.currentTimeMillis();
        // 进度刷新做节流，避免每秒多次通知更新
        if (busy && busy == lastBusy && now - lastNotify < MIN_INTERVAL) return;
        update(tasks);
    }

    private void update(List<DownloadTask> tasks) {
        boolean busy = DownloadManager.get().hasBusy();
        if (!busy) {
            running = false;
            // 先发空闲通知，再移除同服务的前台通知，最后停服务
            notifyIdle(tasks);
            stopForeground(true);
            stopSelf();
            return;
        }
        lastNotify = System.currentTimeMillis();
        lastBusy = busy;
        clearIdle();
        // 统一走 startForeground：stopForeground 后再被 startForegroundService 拉起时不会漏掉前台状态
        startForegroundCompat(notification(tasks));
    }

    private void clearIdle() {
        try {
            NotificationManagerCompat.from(this).cancel(NOTIFY_IDLE_ID);
        } catch (Throwable ignored) {
        }
    }

    private void notifyIdle(List<DownloadTask> tasks) {
        if (tasks.isEmpty()) {
            clearIdle();
            return;
        }
        boolean paused = false;
        for (DownloadTask task : tasks) {
            if (task.getState() == DownloadTask.State.CANCELED || task.getState() == DownloadTask.State.WAITING) paused = true;
        }
        try {
            NotificationManagerCompat.from(this).notify(NOTIFY_IDLE_ID, idleNotification(paused));
        } catch (Throwable ignored) {
        }
    }

    private Notification notification(List<DownloadTask> tasks) {
        int percent = overallProgress(tasks);
        long speed = 0;
        for (DownloadTask task : tasks) if (task.getState() == DownloadTask.State.RUNNING) speed += task.getSpeed();
        String text = getString(R.string.download_notification_text, percent, DownloadTask.formatSpeed(speed));
        NotificationCompat.Builder builder = baseBuilder()
                .setContentTitle(getString(R.string.download_notification_title))
                .setContentText(text)
                .setProgress(100, percent, percent <= 0)
                .setOngoing(true)
                .addAction(0, getString(R.string.download_action_pause_all), pauseIntent());
        return builder.build();
    }

    private Notification idleNotification(boolean paused) {
        String title = getString(paused ? R.string.download_notification_paused : R.string.download_notification_done);
        return baseBuilder()
                .setContentTitle(title)
                .setContentText(getString(R.string.download_notification_open))
                .setOngoing(false)
                .setAutoCancel(true)
                .build();
    }

    private NotificationCompat.Builder baseBuilder() {
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(openIntent())
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
    }

    private int overallProgress(List<DownloadTask> tasks) {
        int total = 0;
        int count = 0;
        for (DownloadTask task : tasks) {
            if (task.getState() == DownloadTask.State.DONE) continue;
            total += task.getProgress();
            count++;
        }
        return count == 0 ? 100 : total / count;
    }

    private PendingIntent openIntent() {
        Intent intent = new Intent();
        intent.setClassName(getPackageName(), "com.fongmi.android.tv.ui.activity.DownloadCenterActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent pauseIntent() {
        Intent intent = new Intent(this, DownloadService.class).setAction(ACTION_PAUSE);
        return PendingIntent.getService(this, 1, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void createChannel() {
        NotificationManagerCompat manager = NotificationManagerCompat.from(this);
        manager.createNotificationChannel(new NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.download_notification_title))
                .setShowBadge(false)
                .build());
    }

    private void startForegroundCompat(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFY_ID, notification);
            }
        } catch (Throwable e) {
            Log.e("DownloadService", "startForeground failed", e);
        }
    }

    private void acquireLocks() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, BuildConfig.APPLICATION_ID + ":download");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Throwable ignored) {
        }
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, BuildConfig.APPLICATION_ID + ":download");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Throwable ignored) {
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignored) {
        }
        wakeLock = null;
        wifiLock = null;
    }
}
