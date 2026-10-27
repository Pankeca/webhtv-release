package com.fongmi.android.tv.track;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Keep;
import com.github.catvod.utils.Prefers;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class UpdateTrack {

    private static final String KEY = "update_tracks";
    public static final int DAYS_EVERY = 0;
    public static final int DAYS_NONE = -1;

    private String key;
    private String siteKey;
    private String vodId;
    private String vodName;
    private String vodPic;
    private String siteName;
    private int days;
    private int count;
    private int seen;
    private long time;

    public static List<UpdateTrack> list() {
        try {
            String json = Prefers.getString(KEY, "");
            if (TextUtils.isEmpty(json)) return new ArrayList<>();
            List<UpdateTrack> items = gson().fromJson(json, type());
            return items == null ? new ArrayList<>() : items;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static UpdateTrack find(String key) {
        for (UpdateTrack item : list()) if (TextUtils.equals(item.key, key)) return item;
        return null;
    }

    public static boolean has(String key) {
        return find(key) != null;
    }

    public static boolean hasUpdate(String key) {
        UpdateTrack item = find(key);
        return item != null && item.count > item.seen;
    }

    public static boolean hasAnyUpdate() {
        for (UpdateTrack item : list()) if (item.count > item.seen) return true;
        return false;
    }

    public static void markAllSeen() {
        List<UpdateTrack> items = list();
        boolean changed = false;
        for (UpdateTrack item : items) {
            if (item.seen < item.count) {
                item.seen = item.count;
                changed = true;
            }
        }
        if (changed) persist(items);
    }

    public static void set(Keep keep, int days) {
        if (keep == null) return;
        UpdateTrack item = find(keep.getKey());
        if (item == null) {
            item = new UpdateTrack();
            item.key = keep.getKey();
            item.seen = 0;
            item.count = 0;
        }
        item.siteKey = keep.getSiteKey();
        item.vodId = keep.getVodId();
        item.vodName = keep.getVodName();
        item.vodPic = keep.getVodPic();
        item.siteName = keep.getSiteName();
        item.days = days;
        item.time = System.currentTimeMillis();
        save(item);
    }

    public static void remove(String key) {
        UpdateTrack item = find(key);
        if (item == null) return;
        List<UpdateTrack> items = list();
        items.remove(item);
        persist(items);
    }

    public static void markSeen(String key, int count) {
        UpdateTrack item = find(key);
        if (item == null) return;
        item.seen = Math.max(item.seen, count);
        item.count = Math.max(item.count, count);
        save(item);
    }

    public static void save(UpdateTrack item) {
        List<UpdateTrack> items = list();
        items.removeIf(it -> TextUtils.equals(it.key, item.key));
        items.add(item);
        persist(items);
    }

    private static void persist(List<UpdateTrack> items) {
        Prefers.put(KEY, gson().toJson(items));
        com.fongmi.android.tv.playback.KeepCloudBackup.schedule(30_000);
    }

    private static Gson gson() {
        return App.gson();
    }

    private static Type type() {
        return new TypeToken<List<UpdateTrack>>() {
        }.getType();
    }

    public static int countEpisodes(List<Flag> flags) {
        int max = 0;
        for (Flag flag : flags == null ? new ArrayList<Flag>() : flags) {
            String urls = flag.getUrls();
            if (TextUtils.isEmpty(urls)) continue;
            int count = 0;
            for (String part : urls.split("#")) if (!TextUtils.isEmpty(part.trim())) count++;
            max = Math.max(max, count);
        }
        return max;
    }

    public static int bitFor(Calendar calendar) {
        int weekday = calendar.get(Calendar.DAY_OF_WEEK); // 1=周日..7=周六
        return 1 << (weekday == Calendar.SUNDAY ? 6 : weekday - 2); // bit0=周一..bit6=周日
    }

    public String getKey() {
        return key;
    }

    public String getSiteKey() {
        return siteKey;
    }

    public String getVodId() {
        return vodId;
    }

    public String getVodName() {
        return vodName;
    }

    public String getVodPic() {
        return vodPic;
    }

    public String getSiteName() {
        return siteName;
    }

    public int getDays() {
        return days;
    }

    public int getCount() {
        return count;
    }

    public int getSeen() {
        return seen;
    }

    public long getTime() {
        return time;
    }

    public void setCount(int count) {
        this.count = count;
        this.time = System.currentTimeMillis();
    }
}
