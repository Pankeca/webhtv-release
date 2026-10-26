package com.fongmi.android.tv.download;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DownloadManager {

    private static final int MAX_CONCURRENT = 2;
    private static final String KEY = "download_tasks";
    private static final DownloadManager INSTANCE = new DownloadManager();

    private final List<DownloadTask> tasks = new CopyOnWriteArrayList<>();
    private final List<DownloadTask.Listener> listeners = new ArrayList<>();
    private boolean ticking;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!hasBusy()) {
                ticking = false;
                return;
            }
            for (DownloadTask task : tasks) task.tickSpeed();
            emitLight();
            App.post(this, 1000);
        }
    };

    public static DownloadManager get() {
        return INSTANCE;
    }

    private DownloadManager() {
        restore();
        reconcile();
    }

    private void restore() {
        try {
            String json = Prefers.getString(KEY, "");
            if (TextUtils.isEmpty(json)) return;
            List<DownloadTask> saved = App.gson().fromJson(json, new com.google.gson.reflect.TypeToken<List<DownloadTask>>() {
            }.getType());
            if (saved == null) return;
            for (DownloadTask task : saved) {
                if (task == null) continue;
                // 历史遗留的“仅文件”记录没有下载地址，只要已完成就保留；未完成的必须能重试
                if (TextUtils.isEmpty(task.getEpisodeUrl()) && task.getState() != DownloadTask.State.DONE && task.getState() != DownloadTask.State.MISSING) continue;
                if (task.isBusy()) task.setState(DownloadTask.State.WAITING);
                tasks.add(task);
            }
        } catch (Throwable ignored) {
        }
    }

    private void persist() {
        try {
            Prefers.put(KEY, App.gson().toJson(tasks));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 让任务记录与磁盘实际文件保持一致：DONE 但文件没了 -> MISSING；
     * MISSING 但文件又出现了 -> DONE；磁盘上有文件但没记录（历史遗留）-> 补一条 DONE 记录。
     * 启动、打开下载中心、播放前都会调用。
     */
    public void reconcile() {
        boolean changed = adoptOrphanFiles();
        for (DownloadTask task : tasks) {
            boolean exists = DownloadTask.findLocal(task.getVodName(), task.getEpisodeName()) != null;
            if (task.getState() == DownloadTask.State.DONE && !exists) {
                task.setState(DownloadTask.State.MISSING);
                changed = true;
            } else if (task.getState() == DownloadTask.State.MISSING && exists) {
                task.setState(DownloadTask.State.DONE);
                changed = true;
            }
            // 补录的历史记录没有下载地址，文件又没了就无法重下，直接清理
            if (!exists && TextUtils.isEmpty(task.getEpisodeUrl())) {
                tasks.remove(task);
                changed = true;
            }
        }
        if (changed) {
            persist();
            for (DownloadTask.Listener listener : new ArrayList<>(listeners)) {
                try {
                    listener.onChange(tasks());
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 把 Downloads/webhtv 下没有记录的视频文件补进已下载列表，避免"文件在、列表里没有"的两套状态。 */
    private boolean adoptOrphanFiles() {
        try {
            File root = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "webhtv");
            File[] dirs = root.listFiles();
            if (dirs == null) return false;
            java.util.HashSet<String> known = new java.util.HashSet<>();
            for (DownloadTask task : tasks) known.add(DownloadTask.sanitize(task.getVodName()) + "/" + DownloadTask.sanitize(task.getEpisodeName()));
            boolean changed = false;
            for (File dir : dirs) {
                if (!dir.isDirectory()) continue;
                File[] files = dir.listFiles();
                if (files == null) continue;
                for (File file : files) {
                    String fileName = file.getName();
                    int dot = fileName.lastIndexOf('.');
                    if (dot <= 0 || file.length() <= 0) continue;
                    String ext = fileName.substring(dot + 1);
                    if (!DownloadTask.isVideoExt(ext)) continue;
                    String episodeName = fileName.substring(0, dot);
                    if (!known.add(dir.getName() + "/" + episodeName)) continue;
                    DownloadTask task = new DownloadTask("", "", "", dir.getName(), "", "", episodeName, "");
                    task.setState(DownloadTask.State.DONE);
                    task.setProgressValue(100);
                    tasks.add(task);
                    changed = true;
                }
            }
            return changed;
        } catch (Throwable e) {
            return false;
        }
    }

    public DownloadTask find(String vodName, String episodeName) {
        for (DownloadTask task : tasks) if (task.getVodName().equals(vodName) && task.getEpisodeName().equals(episodeName)) return task;
        return null;
    }

    public void addListener(DownloadTask.Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(DownloadTask.Listener listener) {
        listeners.remove(listener);
    }

    public void emit() {
        persist();
        // 先通知服务：空闲时由服务自己发完成通知再停掉，避免这里先 stop 导致通知丢失
        notifyListeners();
        syncService();
    }

    /** 进度类刷新：不写磁盘、不改变服务状态，只通知界面与通知栏。 */
    public void emitLight() {
        syncService();
        notifyListeners();
    }

    private void notifyListeners() {
        for (DownloadTask.Listener listener : new ArrayList<>(listeners)) {
            try {
                listener.onChange(tasks());
            } catch (Throwable ignored) {
            }
        }
    }

    private void syncService() {
        if (hasBusy()) {
            startTicker();
            DownloadService.start(App.get());
        } else {
            DownloadService.stop(App.get());
        }
    }

    private void startTicker() {
        if (ticking) return;
        ticking = true;
        App.post(ticker, 1000);
    }

    public boolean enqueue(DownloadTask task) {
        DownloadTask existing = find(task.getVodName(), task.getEpisodeName());
        if (existing != null) {
            if (existing.isBusy()) return false;
            // 已下载且文件还在，不必重复覆盖
            if (existing.getState() == DownloadTask.State.DONE && DownloadTask.findLocal(task.getVodName(), task.getEpisodeName()) != null) return false;
            // 失败/暂停/文件缺失的旧记录直接替换，避免同一集留下两条记录
            tasks.remove(existing);
        }
        tasks.add(task);
        pump();
        emit();
        return true;
    }

    public void cancel(String id) {
        for (DownloadTask task : tasks) if (task.getId().equals(id)) task.cancel();
        emit();
    }

    public void remove(DownloadTask target, boolean deleteFile) {
        if (target.isBusy()) target.cancel();
        if (deleteFile) {
            DownloadTask.deleteLocal(target.getVodName(), target.getEpisodeName());
            Path.clear(M3u8.tempDir(target.getId()));
        }
        tasks.remove(target);
        persist();
        emit();
    }

    public void retry(DownloadTask target) {
        target.retry();
        pump();
        emit();
    }

    public void resumeAll() {
        resume(tasks());
    }

    /** 恢复上次未完成的任务（App 重启后回到下载中）。 */
    public void resumeNow() {
        pump();
        emit();
    }

    public void resume(List<DownloadTask> targets) {
        for (DownloadTask task : new ArrayList<>(targets)) {
            if (!tasks.contains(task)) continue;
            // 没有直链的记录重试也只会失败
            if (TextUtils.isEmpty(task.getEpisodeUrl())) continue;
            if (task.getState() == DownloadTask.State.FAILED || task.getState() == DownloadTask.State.CANCELED || task.getState() == DownloadTask.State.MISSING) {
                retry(task);
            }
        }
        pump();
        emit();
    }

    public void pause(List<DownloadTask> targets) {
        for (DownloadTask task : new ArrayList<>(targets)) if (task.isBusy()) task.cancel();
        emit();
    }

    public List<DownloadTask> tasks() {
        List<DownloadTask> items = new ArrayList<>(tasks);
        items.sort((a, b) -> Boolean.compare(b.isBusy(), a.isBusy()));
        return items;
    }

    public boolean hasBusy() {
        for (DownloadTask task : tasks) if (task.isBusy()) return true;
        return false;
    }

    private void pump() {
        int running = 0;
        for (DownloadTask task : tasks) if (task.getState() == DownloadTask.State.RUNNING) running++;
        for (DownloadTask task : tasks) {
            if (running >= MAX_CONCURRENT) break;
            if (task.getState() == DownloadTask.State.WAITING) {
                task.start();
                running++;
            }
        }
    }
}
