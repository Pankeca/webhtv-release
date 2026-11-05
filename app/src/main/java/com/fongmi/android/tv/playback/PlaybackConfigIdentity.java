package com.fongmi.android.tv.playback;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.setting.AccountStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class PlaybackConfigIdentity {

    /** 账号级固定分区种子：TVBox 与 WebHTV 两端冻结为同一个常量。 */
    private static final String ACCOUNT_SEED = "tvbox-account";
    /** 账号级观看历史分区 key（sha256 前 24 位），不再随当前订阅地址变化。 */
    public static final String ACCOUNT_KEY = sha256(ACCOUNT_SEED).substring(0, 24);

    private PlaybackConfigIdentity() {
    }

    public static String currentKey() {
        // 冻结规则 B：观看历史统一落在账号级固定分区，换点播源不再换分区
        return ACCOUNT_KEY;
    }

    public static String currentName() {
        return safe(VodConfig.getDesc());
    }

    public static String keyForCid(int cid) {
        return ACCOUNT_KEY;
    }

    public static String nameForCid(int cid) {
        Config config = Config.find(cid);
        if (config == null || TextUtils.isEmpty(config.getDesc())) return currentName();
        return config.getDesc();
    }

    public static int cidForKey(String configKey) {
        configKey = normalizeKey(configKey);
        if (TextUtils.isEmpty(configKey)) return 0;
        // 账号分区里的记录一律落到本机当前配置（cid 只在本机使用）
        if (TextUtils.equals(configKey, ACCOUNT_KEY)) return VodConfig.getCid();
        // 旧分区（按订阅地址派生）仅用于首次切换时的一次性迁移读取
        for (Config config : Config.getAll(0)) {
            if (config == null || TextUtils.isEmpty(config.getUrl())) continue;
            if (TextUtils.equals(configKey, keyForUrl(config.getUrl()))) return config.getId();
        }
        return 0;
    }

    public static String keyForUrl(String url) {
        url = safe(url).trim();
        return TextUtils.isEmpty(url) ? "" : sha256(url);
    }

    public static String normalizeKey(String value) {
        return safe(value).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 增量游标的本机存储作用域。账号分区按 uid 隔离，
     * 避免切换账号后复用上一个账号的游标而漏拉增量；网络请求仍使用 ACCOUNT_KEY。
     */
    public static String cursorScope(String configKey) {
        String key = normalizeKey(configKey);
        if (!TextUtils.equals(key, ACCOUNT_KEY)) return key;
        return key + "#" + AccountStore.uid();
    }

    /** 账号分区的游标 key（含历史未按账号隔离的裸 key）。 */
    public static boolean isAccountCursorKey(String key) {
        key = normalizeKey(key);
        return TextUtils.equals(key, ACCOUNT_KEY) || key.startsWith(ACCOUNT_KEY + "#");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(safe(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) builder.append(String.format(Locale.ROOT, "%02x", b));
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
