package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.BuildConfig;
import com.github.catvod.utils.Prefers;

public final class AccountStore {

    private static final String KEY_UID = "account_uid";
    private static final String KEY_NAME = "account_name";
    private static final String KEY_TOKEN = "account_token";
    private static final String KEY_SERVER = "account_server";
    private static final String KEY_ADMIN = "account_admin_unlocked";

    private AccountStore() {
    }

    public static String server() {
        return Prefers.getString(KEY_SERVER, "");
    }

    public static void server(String server) {
        Prefers.put(KEY_SERVER, server == null ? "" : server.trim());
    }

    /** 管理员可覆盖；普通用户直接使用安装包内置的默认服务地址。 */
    public static String effectiveServer() {
        String custom = server();
        return TextUtils.isEmpty(custom) ? BuildConfig.DEFAULT_CLOUD_SERVER.trim() : custom;
    }

    public static boolean hasServerOverride() {
        return !TextUtils.isEmpty(server());
    }

    public static boolean isLogin() {
        return !TextUtils.isEmpty(token());
    }

    public static long uid() {
        return Prefers.getLong(KEY_UID, 0);
    }

    public static String name() {
        return Prefers.getString(KEY_NAME, "");
    }

    public static String token() {
        return Prefers.getString(KEY_TOKEN, "");
    }

    public static void login(long uid, String name, String token) {
        Prefers.put(KEY_UID, uid);
        Prefers.put(KEY_NAME, name);
        Prefers.put(KEY_TOKEN, token);
    }

    public static void updateToken(String token) {
        Prefers.put(KEY_TOKEN, token);
    }

    public static void logout() {
        Prefers.put(KEY_UID, 0L);
        Prefers.put(KEY_NAME, "");
        Prefers.put(KEY_TOKEN, "");
        Prefers.put(KEY_ADMIN, false);
    }

    public static boolean isAdminUnlocked() {
        return Prefers.getBoolean(KEY_ADMIN, false);
    }

    public static void setAdminUnlocked(boolean unlocked) {
        Prefers.put(KEY_ADMIN, unlocked);
    }
}
