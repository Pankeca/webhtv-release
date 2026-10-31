package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.github.catvod.utils.Prefers;

/**
 * 首页内容来源（三选一）。
 *
 * <p>首页本来是整个 App 的入口，站点自带什么就显示什么。这里给用户一个开关：
 * 把首页换成「豆瓣热播」「站点推荐」「最近观看」中的一种，四选一（含关闭）。
 *
 * <p>为什么做成单选而不是三个独立开关——首页只有一个，同时开三个只能靠一堆子 tab
 * 分流，反而把首页搞成第二个分类页。单选最符合「首页显示什么」这个语义。
 *
 * <p>默认 {@link #OFF}：老用户升级上来首页和以前一模一样。
 */
public final class RecommendSetting {

    public static final String OFF = "off";
    public static final String DOUBAN = "douban";
    public static final String SITE = "site";
    public static final String HISTORY = "history";

    private static final String KEY_SOURCE = "recommend_home_source";

    private RecommendSetting() {
    }

    public static String getSource() {
        String value = Prefers.getString(KEY_SOURCE);
        if (DOUBAN.equals(value) || SITE.equals(value) || HISTORY.equals(value)) return value;
        return OFF;
    }

    public static void setSource(String source) {
        Prefers.put(KEY_SOURCE, TextUtils.isEmpty(source) ? OFF : source);
    }

    public static boolean isOff() {
        return OFF.equals(getSource());
    }

    /** 首页换成外部数据源（豆瓣/历史）时，卡片没有站点 key，点了只能按剧名搜。 */
    public static boolean isExternal(String source) {
        return DOUBAN.equals(source) || HISTORY.equals(source);
    }
}
