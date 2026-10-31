package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.update.OciArtifact;
import com.fongmi.android.tv.utils.AppVersion;

public class Update {

    public static final String CHANNEL_STABLE = "stable";
    public static final String CHANNEL_BETA = "beta";

    public String channel;
    public String name;
    public String versionName;
    public String desc;
    public String notes;
    public String apk;
    public String apkUrl;
    public String githubUrl;
    public String error;
    public String sha256;
    public OciArtifact oci;
    public int code;
    public long size;

    public static Update empty(String channel) {
        Update update = new Update();
        update.channel = channel;
        return update;
    }

    public boolean isBeta() {
        return CHANNEL_BETA.equals(channel);
    }

    public boolean hasManifest() {
        return !TextUtils.isEmpty(name) && (!TextUtils.isEmpty(githubUrl) || oci != null && oci.isValid());
    }

    /**
     * 清单版本是否比本机新。
     *
     * <p>旧实现是 {@code code != VERSION_CODE || !AppVersion.isCurrent(name)}，问题在于
     * {@code name} 是清单里的<b>资产文件名</b>（例如 {@code mobile-arm64_v8a.apk}），
     * 它永远不可能等于本机的「版本号-构建时间」，于是第二个条件恒真 ——
     * 结果就是「已经是最新版本，仍然提示发现新版本」，点下去还会把 150MB 整个重下一遍。
     *
     * <p>现在只认版本号：优先比 {@code code}，相等（或清单没写 code）时再比 {@code versionName}。
     * 严格用「大于」判定，清单版本比本机旧也不会提示。
     */
    public boolean hasUpdate() {
        if (!hasManifest()) return false;
        if (code > 0) return code > BuildConfig.VERSION_CODE;
        String remote = AppVersion.stripPrefix(versionName);
        String local = AppVersion.stripPrefix(BuildConfig.VERSION_NAME);
        if (TextUtils.isEmpty(remote)) return false;
        return AppVersion.compare(remote, local) > 0;
    }

    /** 清单里的可读版本号，展示用（不要用资产文件名当版本号）。 */
    public String getVersionLabel() {
        if (!TextUtils.isEmpty(versionName)) return AppVersion.stripPrefix(versionName);
        return AppVersion.stripPrefix(name);
    }

    public String getText() {
        if (!TextUtils.isEmpty(notes)) return notes;
        if (!TextUtils.isEmpty(desc)) return desc;
        return "";
    }
}
