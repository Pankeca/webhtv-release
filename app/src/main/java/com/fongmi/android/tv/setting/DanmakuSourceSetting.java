package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.DanmakuSource;
import com.github.catvod.utils.Prefers;

import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 多渠道弹幕来源的持久化。
 *
 * <p>兼容策略：用户没显式配过多源时，{@link #getSources()} 会拿
 * {@link DanmakuSetting#getEffectiveApiUrl()}（用户设置 → 配置 JSON → 内置默认）
 * 合成出唯一一条来源，行为与加多渠道之前完全一致。一旦用户在来源管理里保存过，
 * 就以用户列表为准，不再走合成逻辑。
 */
public final class DanmakuSourceSetting {

    private static final String KEY_SOURCES = "danmaku_sources";
    private static final String KEY_MERGE = "danmaku_source_merge";

    private DanmakuSourceSetting() {
    }

    private static Type listType() {
        return TypeToken.getParameterized(List.class, DanmakuSource.class).getType();
    }

    /** 用户是否显式配置过来源列表。 */
    public static boolean hasCustomSources() {
        return !Prefers.getString(KEY_SOURCES).isEmpty();
    }

    /** 全部来源（含被禁用的），顺序即优先级。 */
    public static List<DanmakuSource> getSources() {
        List<DanmakuSource> stored = readStored();
        // 只有「从未配置过」才回落到合成来源；用户显式删空后要保持为空，
        // 否则删掉最后一条来源会立刻被合成出来一条，看起来像删不掉
        return hasCustomSources() ? stored : synthesized();
    }

    /** 参与搜索的来源：启用且地址合法，顺序即优先级。 */
    public static List<DanmakuSource> getEnabledSources() {
        List<DanmakuSource> result = new ArrayList<>();
        for (DanmakuSource source : getSources()) {
            if (source != null && source.isEnabled() && source.isValid()) result.add(source);
        }
        return result;
    }

    /** 自动匹配是否并行合并所有来源；关闭时按顺序取第一个有结果的来源。 */
    public static boolean isMergeAll() {
        return Prefers.getBoolean(KEY_MERGE);
    }

    public static void putMergeAll(boolean merge) {
        Prefers.put(KEY_MERGE, merge);
    }

    public static void saveAll(List<DanmakuSource> sources) {
        if (sources == null) return;
        List<DanmakuSource> clean = new ArrayList<>();
        for (DanmakuSource source : sources) {
            if (source == null || TextUtils.isEmpty(source.getUrl())) continue;
            if (TextUtils.isEmpty(source.getId())) source.setId(java.util.UUID.randomUUID().toString());
            clean.add(source);
        }
        Prefers.put(KEY_SOURCES, App.gson().toJson(clean));
    }

    public static void add(DanmakuSource source) {
        List<DanmakuSource> sources = new ArrayList<>(getSources());
        sources.add(source);
        saveAll(sources);
    }

    public static void update(DanmakuSource source) {
        List<DanmakuSource> sources = new ArrayList<>(getSources());
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).getId().equals(source.getId())) {
                sources.set(i, source);
                saveAll(sources);
                return;
            }
        }
        sources.add(source);
        saveAll(sources);
    }

    public static void remove(String id) {
        List<DanmakuSource> sources = new ArrayList<>(getSources());
        sources.removeIf(item -> item.getId().equals(id));
        saveAll(sources);
    }

    /** 上移一位，返回是否真的动了。 */
    public static boolean moveUp(String id) {
        return move(id, -1);
    }

    /** 下移一位，返回是否真的动了。 */
    public static boolean moveDown(String id) {
        return move(id, 1);
    }

    public static void toggle(String id) {
        List<DanmakuSource> sources = new ArrayList<>(getSources());
        for (DanmakuSource source : sources) {
            if (source.getId().equals(id)) source.setEnabled(!source.isEnabled());
        }
        saveAll(sources);
    }

    /**
     * 把内置公益源补齐到列表里（已在列表中的地址跳过），返回新增条数。
     *
     * <p>不覆盖、不删除用户自己加的来源，只做「补齐」，避免一按就把用户配置冲掉。
     * {@code primaryOnly} 只补实测还能应答的主力源，防止一次塞进十几个死链拖慢搜索。
     */
    /** 单独添加一条内置源，返回是否真的新增（已存在则不动）。 */
    public static boolean addPreset(DanmakuPreset.Item item) {
        if (item == null) return false;
        List<DanmakuSource> sources = new ArrayList<>(getSources());
        String url = normalize(item.url);
        for (DanmakuSource source : sources) {
            if (normalize(source.getUrl()).equals(url)) return false;
        }
        sources.add(DanmakuPreset.toSource(item));
        saveAll(sources);
        return true;
    }

    /** 某个内置源是否已经在列表里。 */
    public static boolean hasPreset(DanmakuPreset.Item item) {
        if (item == null) return false;
        String url = normalize(item.url);
        for (DanmakuSource source : getSources()) {
            if (normalize(source.getUrl()).equals(url)) return true;
        }
        return false;
    }

    public static int addMissingPresets(boolean primaryOnly) {
        List<DanmakuSource> sources = new ArrayList<>(getSources());
        List<String> existing = new ArrayList<>();
        for (DanmakuSource source : sources) existing.add(normalize(source.getUrl()));
        int added = 0;
        for (DanmakuPreset.Item item : DanmakuPreset.all()) {
            if (primaryOnly && !item.primary) continue;
            if (existing.contains(normalize(item.url))) continue;
            sources.add(DanmakuPreset.toSource(item));
            added++;
        }
        if (added > 0) saveAll(sources);
        return added;
    }

    /** 是否有任何可用来源，供设置页显示状态。 */
    public static int countEnabled() {
        return getEnabledSources().size();
    }

    /**
     * 这个弹幕地址是否来自需要放宽证书校验的来源。
     *
     * <p>播放器拉取弹幕 XML 用的是 media3 那条链路，它有独立的 HTTP 客户端；
     * 来源是 IP 直连时（证书 CN 与 IP 不匹配）必须把这个客户端换成宽松校验的，
     * 否则搜索能成、拉取却一定失败——就是「搜到结果但没弹幕」的另一半原因。
     */
    public static boolean needsLooseTls(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String host = hostOf(url);
        if (TextUtils.isEmpty(host)) return false;
        for (DanmakuSource source : getSources()) {
            if (source == null || !source.isInsecure()) continue;
            if (host.equalsIgnoreCase(hostOf(source.getUrl()))) return true;
        }
        return false;
    }

    private static String hostOf(String url) {
        try {
            String host = android.net.Uri.parse(url).getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            return "";
        }
    }

    private static String normalize(String url) {
        if (url == null) return "";
        String value = url.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value.toLowerCase();
    }

    private static boolean move(String id, int delta) {
        List<DanmakuSource> sources = new ArrayList<>(getSources());
        int from = indexOf(sources, id);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= sources.size()) return false;
        Collections.swap(sources, from, to);
        saveAll(sources);
        return true;
    }

    private static int indexOf(List<DanmakuSource> sources, String id) {
        for (int i = 0; i < sources.size(); i++) if (sources.get(i).getId().equals(id)) return i;
        return -1;
    }

    private static List<DanmakuSource> readStored() {
        String json = Prefers.getString(KEY_SOURCES);
        if (TextUtils.isEmpty(json)) return Collections.emptyList();
        try {
            List<DanmakuSource> items = App.gson().fromJson(json, listType());
            if (items == null) return Collections.emptyList();
            for (DanmakuSource item : items) if (item != null) applyTlsHint(item);
            return items;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 老版本存的来源没有 insecure 字段，IP 直连的源会被标准证书校验拦掉（证书 CN 与 IP 不匹配）。
     * 读取时按地址补一次，避免用户升级后原有来源突然全部失效。
     */
    private static void applyTlsHint(DanmakuSource source) {
        if (source.isInsecure()) return;
        try {
            String host = android.net.Uri.parse(source.getUrl()).getHost();
            if (host != null && host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) source.setInsecure(true);
        } catch (Exception ignored) {
        }
    }

    /** 没配过多源时，用现有的单一地址合成一条，保证旧行为不变。 */
    private static List<DanmakuSource> synthesized() {
        String url = DanmakuSetting.getEffectiveApiUrl();
        if (TextUtils.isEmpty(url) || !DanmakuSetting.isValidApiUrl(url)) return Collections.emptyList();
        DanmakuSource source = DanmakuSource.create("", url);
        source.setId("builtin");
        List<DanmakuSource> list = new ArrayList<>();
        list.add(source);
        return list;
    }
}
