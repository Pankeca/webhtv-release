package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonObject;

/**
 * 点播/直播源「按 URL」的本地删除标记。
 *
 * <p>冻结规则 C：WebHTV 只读合并 TVBox 的 tvbox_config 时按 URL 取并集；
 * 用户在本机删掉的地址必须记住，否则对方备份下次同步又会把它加回来。
 */
public final class TvboxUrlTombstoneStore {

    private static final String KEY = "tvbox_config_deleted_urls";

    private TvboxUrlTombstoneStore() {
    }

    public static synchronized void mark(String url, int type) {
        if (TextUtils.isEmpty(url)) return;
        JsonObject object = load();
        object.addProperty(identity(url, type), System.currentTimeMillis());
        Prefers.put(KEY, object.toString());
    }

    public static synchronized boolean isDeleted(String url, int type) {
        if (TextUtils.isEmpty(url)) return false;
        JsonObject object = load();
        return object != null && object.has(identity(url, type));
    }

    /** 用户在本机重新添加/启用同一地址时解除标记。 */
    public static synchronized void clear(String url, int type) {
        if (TextUtils.isEmpty(url)) return;
        JsonObject object = load();
        if (object != null && object.remove(identity(url, type)) != null) Prefers.put(KEY, object.toString());
    }

    private static JsonObject load() {
        try {
            JsonObject object = App.gson().fromJson(Prefers.getString(KEY, "{}"), JsonObject.class);
            return object == null ? new JsonObject() : object;
        } catch (Throwable e) {
            return new JsonObject();
        }
    }

    private static String identity(String url, int type) {
        return type + "|" + url.trim();
    }
}
