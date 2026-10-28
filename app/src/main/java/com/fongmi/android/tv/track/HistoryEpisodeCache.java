package com.fongmi.android.tv.track;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/** 缓存最近观看剧集的最新集数，保证未追更的记录也能显示「更新至第几集」。 */
public final class HistoryEpisodeCache {

    private static final String KEY = "history_episode_counts";
    private static Map<String, Integer> cache;

    private HistoryEpisodeCache() {
    }

    public static synchronized int get(String key) {
        if (TextUtils.isEmpty(key)) return 0;
        Integer count = load().get(key);
        return count == null ? 0 : count;
    }

    public static synchronized void put(String key, int count) {
        if (TextUtils.isEmpty(key) || count <= 0) return;
        Map<String, Integer> map = load();
        if (map.containsKey(key) && map.get(key) == count) return;
        map.put(key, count);
        Prefers.put(KEY, App.gson().toJson(map));
    }

    private static Map<String, Integer> load() {
        if (cache != null) return cache;
        try {
            String json = Prefers.getString(KEY, "");
            if (!TextUtils.isEmpty(json)) {
                Map<String, Integer> saved = App.gson().fromJson(json, type());
                if (saved != null) return cache = saved;
            }
        } catch (Exception ignored) {
        }
        return cache = new HashMap<>();
    }

    private static Type type() {
        return new TypeToken<Map<String, Integer>>() {
        }.getType();
    }
}
