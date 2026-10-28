package com.fongmi.android.tv.track;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.crawler.SpiderDebug;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class UpdateTracker {

    private static final long INTERVAL_HOURS = 6;
    private static ScheduledFuture<?> future;

    private UpdateTracker() {
    }

    public interface CheckCallback {

        void done(String message);
    }

    /** 最近观看里的单条待更新目标（含未追更的记录）。 */
    public static class Target {

        public final String key;
        public final String siteKey;
        public final String vodId;
        public final String vodName;
        public final int knownCount;

        public Target(String key, String siteKey, String vodId, String vodName, int knownCount) {
            this.key = key;
            this.siteKey = siteKey;
            this.vodId = vodId;
            this.vodName = vodName;
            this.knownCount = knownCount;
        }
    }

    public static synchronized void init() {
        if (future != null) return;
        // 启动 30 秒后先跑一轮，之后每 6 小时检查一次，避免开启追更后长时间看不到更新集数
        future = Task.scheduler().scheduleWithFixedDelay(UpdateTracker::checkSafe, 30, INTERVAL_HOURS * 3600, TimeUnit.SECONDS);
    }

    private static void checkSafe() {
        try {
            checkNow();
        } catch (Throwable e) {
            SpiderDebug.log("track", "check failed %s", e.toString());
        }
    }

    public static void checkNow() {
        List<UpdateTrack> tracks = UpdateTrack.list();
        for (UpdateTrack track : tracks) {
            if (!shouldCheck(track)) continue;
            checkOne(track);
        }
    }

    private static boolean shouldCheck(UpdateTrack track) {
        if (TextUtils.isEmpty(track.getSiteKey()) || TextUtils.isEmpty(track.getVodId())) return false;
        if (track.getDays() != UpdateTrack.DAYS_EVERY) {
            Calendar calendar = Calendar.getInstance();
            if ((track.getDays() & UpdateTrack.bitFor(calendar)) == 0) return false;
        }
        return true;
    }

    /** 手动更新指定追更项，完成后在主线程回传更新详情（供「我的追更」「最近观看」的更新按钮使用）。 */
    public static void checkKeys(List<String> keys, CheckCallback callback) {
        Task.submit(() -> {
            int total = 0;
            List<String> details = new ArrayList<>();
            for (String key : keys) {
                UpdateTrack track = UpdateTrack.find(key);
                if (track == null) continue;
                total++;
                try {
                    Result result = SiteApi.detailContent(track.getSiteKey(), track.getVodId());
                    if (result == null || result.getVod() == null) continue;
                    int count = UpdateTrack.countEpisodes(result.getVod().getFlags());
                    if (count <= 0) continue;
                    boolean changed = count > track.getCount();
                    track.setCount(count);
                    UpdateTrack.save(track);
                    if (changed) details.add(ResUtil.getString(R.string.track_update_item, track.getVodName(), count));
                } catch (Throwable e) {
                    SpiderDebug.log("track", "manual check %s failed %s", track.getVodName(), e.toString());
                }
            }
            String message;
            if (total == 0) message = ResUtil.getString(R.string.track_update_none);
            else if (details.isEmpty()) message = ResUtil.getString(R.string.track_update_latest, total);
            else message = ResUtil.getString(R.string.track_update_result, details.size(), TextUtils.join("、", details));
            String result = message;
            App.post(() -> callback.done(result));
        });
    }

    /** 手动更新最近观看的全部记录：追更与未追更都检查，未追更的最新集数写入本地缓存。 */
    public static void checkHistory(List<Target> targets, CheckCallback callback) {
        Task.submit(() -> {
            List<String> details = new ArrayList<>();
            int total = 0;
            for (Target target : targets) {
                if (TextUtils.isEmpty(target.siteKey) || TextUtils.isEmpty(target.vodId)) continue;
                total++;
                try {
                    Result result = SiteApi.detailContent(target.siteKey, target.vodId);
                    if (result == null || result.getVod() == null) continue;
                    int count = UpdateTrack.countEpisodes(result.getVod().getFlags());
                    if (count <= 0) continue;
                    UpdateTrack track = UpdateTrack.find(target.key);
                    if (track != null) {
                        track.setCount(count);
                        UpdateTrack.save(track);
                    }
                    HistoryEpisodeCache.put(target.key, count);
                    if (count > target.knownCount) details.add(ResUtil.getString(R.string.track_update_item, target.vodName, count));
                } catch (Throwable e) {
                    SpiderDebug.log("track", "history check %s failed %s", target.vodName, e.toString());
                }
            }
            String message;
            if (total == 0) message = ResUtil.getString(R.string.track_update_none);
            else if (details.isEmpty()) message = ResUtil.getString(R.string.track_update_latest, total);
            else message = ResUtil.getString(R.string.track_update_result, details.size(), TextUtils.join("、", details));
            String result = message;
            App.post(() -> callback.done(result));
        });
    }

    public static int parseEpisode(String text) {
        if (TextUtils.isEmpty(text)) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(text);
        int count = 0;
        while (matcher.find()) {
            try {
                count = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return count;
    }

    private static void checkOne(UpdateTrack track) {
        try {
            Result result = SiteApi.detailContent(track.getSiteKey(), track.getVodId());
            if (result == null || result.getVod() == null) return;
            int count = UpdateTrack.countEpisodes(result.getVod().getFlags());
            if (count <= 0 || count <= track.getCount()) return;
            track.setCount(count);
            UpdateTrack.save(track);
            App.post(() -> Notify.show(ResUtil.getString(R.string.track_new_ep, track.getVodName(), count)));
        } catch (Throwable e) {
            SpiderDebug.log("track", "check %s failed %s", track.getVodName(), e.toString());
        }
    }
}
