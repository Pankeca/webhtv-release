package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.google.gson.annotations.SerializedName;

import java.util.UUID;

/**
 * 一条弹幕来源（弹幕 API 服务）。
 *
 * <p>与 {@link Danmaku} 的区别：{@link Danmaku} 是「搜索到的一条具体弹幕」，
 * 本类是「去哪里搜索」的接口地址。一个来源通常是一个自建 danmu_api 实例，
 * 返回值里自带平台渠道（如 B 站 / 爱奇艺），渠道分组由 {@link Danmaku#getSourceName()} 承担。
 */
public class DanmakuSource {

    @SerializedName("id")
    private String id;
    @SerializedName("name")
    private String name;
    @SerializedName("url")
    private String url;
    @SerializedName("enabled")
    private boolean enabled;
    /** 该来源的 TLS 证书与主机名不匹配（多为 IP 直连的自签证书），需要放宽校验。 */
    @SerializedName("insecure")
    private boolean insecure;

    public static DanmakuSource create(String name, String url) {
        DanmakuSource source = new DanmakuSource();
        source.id = UUID.randomUUID().toString();
        source.name = name == null ? "" : name.trim();
        source.url = url == null ? "" : url.trim();
        source.enabled = true;
        return source;
    }

    public String getId() {
        return TextUtils.isEmpty(id) ? "" : id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return TextUtils.isEmpty(name) ? "" : name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name.trim();
    }

    public String getUrl() {
        return TextUtils.isEmpty(url) ? "" : url.trim();
    }

    public void setUrl(String url) {
        this.url = url == null ? "" : url.trim();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isInsecure() {
        return insecure;
    }

    public void setInsecure(boolean insecure) {
        this.insecure = insecure;
    }

    /** 列表里展示的名字：没起名就退回域名，避免整行空白。 */
    public String getDisplayName() {
        if (!TextUtils.isEmpty(getName())) return getName();
        return host();
    }

    public String host() {
        try {
            java.net.URI uri = java.net.URI.create(getUrl());
            String host = uri.getHost();
            return TextUtils.isEmpty(host) ? getUrl() : host;
        } catch (Exception e) {
            return getUrl();
        }
    }

    public boolean isValid() {
        return DanmakuSetting.isValidApiUrl(getUrl());
    }

    public DanmakuSource copy() {
        DanmakuSource source = new DanmakuSource();
        source.id = getId();
        source.name = getName();
        source.url = getUrl();
        source.enabled = isEnabled();
        source.insecure = isInsecure();
        return source;
    }

    @NonNull
    @Override
    public String toString() {
        return App.gson().toJson(this);
    }
}
