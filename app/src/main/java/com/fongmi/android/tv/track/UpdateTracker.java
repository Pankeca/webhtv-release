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

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class UpdateTracker {

    private static final long INTERVAL_HOURS = 6;
    private static ScheduledFuture<?> future;

    private UpdateTracker() {
    }

    public static synchronized void init() {
        if (future != null) return;
        future = Task.scheduler().scheduleWithFixedDelay(UpdateTracker::checkSafe, 2, INTERVAL_HOURS, TimeUnit.HOURS);
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
