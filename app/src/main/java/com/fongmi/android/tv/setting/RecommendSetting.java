package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.github.catvod.utils.Prefers;

/**
 * 首页内容来源（三选一）。
 *
 * <p>首页只有一个，所以是单选：豆瓣热播 / 站点推荐 / 最近观看。
 *
 * <p>为什么不带「站点默认」这一项——它和「站点推荐」其实是同一件事：
 * 站点有网页首页（注入站点）就显示网页里注入的内容，没有就显示站点 API 返回的推荐列表。
 * 与其多一个语义重叠的选项，不如让「站点推荐」自己承担这两种情况，默认值也放在它上面，
 * 这样不设置的用户感觉不到任何变化。
 */
public final class RecommendSetting {

    public static final String DOUBAN = "douban";
    public static final String SITE = "site";
    public static final String HISTORY = "history";

    private static final String KEY_SOURCE = "recommend_home_source";

    private RecommendSetting() {
    }

    public static String getSource() {
        String value = Prefers.getString(KEY_SOURCE);
        if (DOUBAN.equals(value) || HISTORY.equals(value)) return value;
        return SITE;
    }

    public static void setSource(String source) {
        Prefers.put(KEY_SOURCE, TextUtils.isEmpty(source) ? SITE : source);
    }

    public static boolean isSite() {
        return SITE.equals(getSource());
    }

    /** 首页换成外部数据源（豆瓣/历史）时，卡片没有站点 key，点了只能按剧名搜。 */
    public static boolean isExternal(String source) {
        return DOUBAN.equals(source) || HISTORY.equals(source);
    }
}
