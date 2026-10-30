package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.bean.DanmakuSource;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置公益弹幕源。
 *
 * <p>来源：从 NewBox 2.1.1 的 {@code DANMAKU_SOURCES} 默认值中提取（该应用的「弹幕 API」
 * 对话框里「恢复默认」写入的就是这份列表，格式为 {@code url|名称} 逗号分隔）。
 * 这里按实测可用性重新排序，并为证书与主机名不匹配的 IP 源显式打开宽松校验。
 *
 * <p>这些是第三方公益服务，随时可能失效或限流，因此界面里必须提供「测速」让用户
 * 自己判断，而不是把某个源写死成默认。
 */
public final class DanmakuPreset {

    /** 一个内置源。insecure 表示该源 TLS 证书与主机名不匹配，需要放宽校验才能连上。 */
    public static final class Item {

        public final String name;
        public final String url;
        public final boolean insecure;
        /** 实测当前仍能应答的「主力源」，一键添加时只加这些，避免一次塞进十几个死链。 */
        public final boolean primary;

        Item(String name, String url, boolean insecure, boolean primary) {
            this.name = name;
            this.url = url;
            this.insecure = insecure;
            this.primary = primary;
        }
    }

    private static final List<Item> ITEMS = new ArrayList<>();

    static {
        // 实测 2026-10 可用：搜索 214 条结果，弹幕 XML 4MB / 4.2 万条
        ITEMS.add(new Item("IP源", "https://172.252.125.145/87654321", true, true));
        // 实测：搜索可用（80 条），剧集弹幕经常 404/503，属于服务端限流
        ITEMS.add(new Item("7777735", "https://danmu.7777735.xyz/87654321", false, true));
        ITEMS.add(new Item("sym", "https://danmu1.sym9233.dpdns.org/87654321", false, true));
        ITEMS.add(new Item("iyo", "https://danmu.iyo.us.ci/theft-dastardly-prognosis-hula-agenda2-dropkick", false, true));
        ITEMS.add(new Item("pizazz", "https://pizazz.us.ci/1314", false, true));
        ITEMS.add(new Item("luosen", "https://dm.ljiaovm.com/luosen", false, true));
        // 以下为 NewBox 默认列表里的其余条目，实测当前多返回 502/404/401，保留备选
        ITEMS.add(new Item("ecs", "http://ecs.dysobo.cn:9321/87654321", false, false));
        ITEMS.add(new Item("快源", "http://43.143.108.212/87654321", false, false));
        ITEMS.add(new Item("阿里云", "http://47.107.188.112:6008/87654321", false, false));
        ITEMS.add(new Item("量大", "http://38.207.186.41/87654321", false, false));
        ITEMS.add(new Item("one-vert", "https://danmu-api-one-vert.vercel.app/87654321", false, false));
        ITEMS.add(new Item("660505", "https://dm.660505.xyz:8443/a123456", false, false));
        ITEMS.add(new Item("103.149", "http://103.149.91.208/87654321", false, false));
        ITEMS.add(new Item("64.90", "http://64.90.11.71/87654321", false, false));
        ITEMS.add(new Item("82.47", "http://82.47.35.224/87654321", false, false));
    }

    private DanmakuPreset() {
    }

    public static List<Item> all() {
        return new ArrayList<>(ITEMS);
    }

    public static int size() {
        return ITEMS.size();
    }

    /** 把内置源转成可持久化的来源对象。 */
    public static List<DanmakuSource> sources() {
        List<DanmakuSource> result = new ArrayList<>();
        for (Item item : ITEMS) result.add(toSource(item));
        return result;
    }

    public static DanmakuSource toSource(Item item) {
        DanmakuSource source = DanmakuSource.create(item.name, item.url);
        source.setInsecure(item.insecure);
        source.setEnabled(true);
        return source;
    }

    /** 某个地址是不是内置源（用于列表里标注）。 */
    public static String nameOf(String url) {
        if (url == null) return "";
        for (Item item : ITEMS) if (item.url.equalsIgnoreCase(url.trim())) return item.name;
        return "";
    }
}
