package com.fongmi.android.tv.playback;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.db.AppDatabase;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 追更/收藏的本地删除墓碑。
 *
 * <p>跨端比较的身份按冻结规则取 key 前两段（siteKey + vodId）加剧名，第三段 cid 只在本机使用，
 * 不参与去重；因此同一个剧目换配置后 key 会变，墓碑也必须按身份而不是整条 key 记录。
 *
 * <p>合并云端时，只要墓碑时间不早于云端记录的更新时间，就拒绝把这条记录恢复回来，
 * 保证「本地删掉的收藏不会被对方备份复活」。
 */
public final class KeepTombstoneStore {

    private static final String KEY = "keep_delete_tombstones";
    /** 身份 → 删除时的原始剧名，上传墓碑时用于和服务端 keepIdentity 对齐（大小写敏感）。 */
    private static final String KEY_NAMES = "keep_delete_tombstone_names";
    private static final long RETENTION_MS = TimeUnit.DAYS.toMillis(180);
    private static final int MAX_UPLOAD = 500;

    private KeepTombstoneStore() {
    }

    /** 追更/收藏的跨端身份：key 前两段 + 剧名，cid 不参与。 */
    public static String identity(Keep keep) {
        return keep == null ? "" : identity(keep.getKey(), keep.getVodName());
    }

    public static String identity(String key, String vodName) {
        String raw = key == null ? "" : key.trim();
        String[] parts = raw.split(AppDatabase.SYMBOL);
        String site = parts.length > 0 ? parts[0] : raw;
        String vod = parts.length > 1 ? parts[1] : "";
        return site + AppDatabase.SYMBOL + vod + AppDatabase.SYMBOL + normalize(vodName);
    }

    public static void mark(Keep keep) {
        if (keep == null) return;
        String identity = identity(keep);
        mark(identity, System.currentTimeMillis());
        rememberName(identity, keep.getVodName());
    }

    /** 应用云端墓碑到本地时调用，保留原始剧名以便再上传时与服务端身份匹配。 */
    public static synchronized void mark(String identity, String vodName, long time) {
        mark(identity, time);
        rememberName(identity, vodName);
    }

    public static synchronized void mark(String identity, long time) {
        if (TextUtils.isEmpty(identity) || time <= 0) return;
        JsonObject object = load();
        long old = value(object, identity);
        if (old >= time) return;
        object.addProperty(identity, time);
        prune(object, System.currentTimeMillis());
        Prefers.put(KEY, object.toString());
    }

    public static synchronized long deletedAt(String identity) {
        return value(load(), identity);
    }

    /**
     * 上传给服务端的墓碑快照：按剧名（原始大小写）匹配，key 作为兜底，
     * 最多 500 条（服务端上限），较新的优先。
     */
    public static synchronized JsonArray snapshot() {
        JsonObject times = load();
        JsonObject names = loadNames();
        List<String> ids = new ArrayList<>(times.keySet());
        ids.sort((a, b) -> Long.compare(value(times, b), value(times, a)));
        JsonArray array = new JsonArray();
        for (String id : ids) {
            if (array.size() >= MAX_UPLOAD) break;
            String[] parts = id.split(AppDatabase.SYMBOL);
            if (parts.length < 3) continue;
            JsonObject item = new JsonObject();
            item.addProperty("key", parts[0] + AppDatabase.SYMBOL + parts[1] + AppDatabase.SYMBOL + "0");
            String name = names.has(id) ? names.get(id).getAsString() : "";
            if (!TextUtils.isEmpty(name)) item.addProperty("vodName", name);
            item.addProperty("deletedAt", value(times, id));
            item.addProperty("deleted", true);
            array.add(item);
        }
        return array;
    }

    private static void rememberName(String identity, String vodName) {
        if (TextUtils.isEmpty(identity) || TextUtils.isEmpty(vodName)) return;
        try {
            JsonObject object = loadNames();
            object.addProperty(identity, vodName.trim());
            Prefers.put(KEY_NAMES, object.toString());
        } catch (Throwable ignored) {
        }
    }

    private static JsonObject loadNames() {
        try {
            JsonObject object = App.gson().fromJson(Prefers.getString(KEY_NAMES, "{}"), JsonObject.class);
            return object == null ? new JsonObject() : object;
        } catch (Throwable e) {
            return new JsonObject();
        }
    }

    private static JsonObject load() {
        try {
            JsonObject object = App.gson().fromJson(Prefers.getString(KEY, "{}"), JsonObject.class);
            return object == null ? new JsonObject() : object;
        } catch (Throwable e) {
            return new JsonObject();
        }
    }

    private static long value(JsonObject object, String identity) {
        try {
            return object != null && object.has(identity) ? object.get(identity).getAsLong() : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    private static void prune(JsonObject object, long now) {
        String[] names = object.keySet().toArray(new String[0]);
        for (String name : names) if (now - value(object, name) > RETENTION_MS) object.remove(name);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
