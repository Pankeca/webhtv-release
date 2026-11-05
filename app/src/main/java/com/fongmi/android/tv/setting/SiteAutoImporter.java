package com.fongmi.android.tv.setting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一键导入站点：把任意网站 URL 变成可注入的 WebHome 站点。
 *
 * <p>与 {@link CspUrlImporter}（导入社区物料：配置/扩展/主页快照/直播源）互补：
 * 本类负责「在线站点」路径——粘贴任意站点地址，自动生成站点条目 + 通用自适应
 * 扩展挂载（{@code local://webhome/universal-enhance.js} + 站点级
 * {@code __fmAutoConfig} 覆写），落地后立即可浏览、可「App播放」。
 *
 * <p>纯字符串/JSON 运算，不碰 Android API，可直接在 JVM 单元测试里跑。
 * 落地装配（写注册表、装脚本资产）由 {@code CustomCspDialog} / {@code WebHomeAssets} 负责。
 */
public final class SiteAutoImporter {

    /** 通用自适应脚本的本地引用（WebHomeAssets 安装到 files/webhome/）。 */
    public static final String SCRIPT_LOCAL_URL = "local://webhome/universal-enhance.js";
    /** 扩展 id 前缀：升级/迁移时可据此识别自动生成的扩展。 */
    public static final String AUTO_ID_PREFIX = "auto-";

    private static final Pattern META_SITE_NAME = Pattern.compile(
            "<meta[^>]+(?:property|name)=[\"']og:site_name[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_SITE_NAME_REVERSE = Pattern.compile(
            "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"']og:site_name[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE = Pattern.compile(
            "<title[^>]*>([^<]+)</title>", Pattern.CASE_INSENSITIVE);
    /** 站名清洗：按这些分隔符取第一段（「片库-4K影视」→「片库」）。 */
    private static final String[] NAME_SEPARATORS = {" - ", "-", "_", "|", "—", "·", ":", "："};

    /** 生成结果：交给对话框装配成 {@code CustomCspSetting.Item}。 */
    public static final class Spec {
        public final String id;
        public final String key;
        public final String name;
        public final String homePage;
        public final String profile;
        public final String extensionsJson;

        Spec(String id, String key, String name, String homePage, String profile, String extensionsJson) {
            this.id = id;
            this.key = key;
            this.name = name;
            this.homePage = homePage;
            this.profile = profile;
            this.extensionsJson = extensionsJson;
        }
    }

    private SiteAutoImporter() {
    }

    // ---------- 判定 ----------

    /**
     * 这段 HTML 是不是一个「在线站点」（而不是社区分享的单文件主页快照）。
     * 判定逻辑在 {@link CspUrlImporter#looksLikeLiveSite}（物料分类是那边的职责），
     * 这里留同名转发，方便站点导入侧按语义调用。
     */
    public static boolean looksLikeLiveSite(String url, String body) {
        return CspUrlImporter.looksLikeLiveSite(url, body);
    }

    // ---------- 站点信息提取 ----------

    /** 站点名：og:site_name → &lt;title&gt; 首段 → host。 */
    public static String siteName(String html, String url) {
        String name = metaSiteName(html);
        if (name.isEmpty()) name = titleSegment(html);
        if (name.isEmpty()) name = hostOf(url);
        return name;
    }

    private static String metaSiteName(String html) {
        if (html == null) return "";
        Matcher matcher = META_SITE_NAME.matcher(html);
        if (matcher.find()) return cleanName(matcher.group(1));
        matcher = META_SITE_NAME_REVERSE.matcher(html);
        if (matcher.find()) return cleanName(matcher.group(1));
        return "";
    }

    private static String titleSegment(String html) {
        if (html == null) return "";
        Matcher matcher = TITLE.matcher(html);
        if (!matcher.find()) return "";
        String title = cleanName(matcher.group(1));
        if (title.isEmpty()) return "";
        for (String separator : NAME_SEPARATORS) {
            int index = title.indexOf(separator);
            if (index >= 2) return title.substring(0, index).trim();
        }
        return title;
    }

    private static String cleanName(String value) {
        if (value == null) return "";
        return value.replace('\uFEFF', ' ').replaceAll("\\s+", " ").trim();
    }

    /**
     * CMS 指纹：给出脚本 profile 提示（运行时启发式是兜底，这里更准）。
     *
     * @return {@code maccms} / {@code halo} / {@code generic}
     */
    public static String fingerprint(String html) {
        if (html == null || html.isEmpty()) return "generic";
        String lower = html.toLowerCase(Locale.ROOT);
        // /voddetail/、/vodplay/ 是 maccms 系特有路径；首页可能只有其一
        if (lower.contains("player_aaaa") || lower.contains("maccms")
                || lower.contains("/voddetail/") || lower.contains("/vodplay/")) return "maccms";
        if (lower.contains("module-play-list") || lower.contains("module-list-play")
                || lower.contains("module-player") || lower.contains("halocms")) return "halo";
        return "generic";
    }

    // ---------- 生成 ----------

    /** 由 URL + 页面 HTML 生成站点装配描述。 */
    public static Spec build(String url, String html) {
        String host = hostOf(url);
        if (host.isEmpty()) throw new IllegalArgumentException("invalid url: " + url);
        String homePage = originOf(url);
        String name = siteName(html, url);
        if (name.isEmpty()) name = host;
        String profile = fingerprint(html);
        String key = siteKey(host);
        String id = AUTO_ID_PREFIX + md5Short(host);
        return new Spec(id, key, name, homePage, profile, extensionsJson(id, host, name, profile));
    }

    /** 站点 key：稳定、可读、与出厂站点（{@code __custom_csp_*}）同一命名空间。 */
    public static String siteKey(String host) {
        String slug = host == null ? "" : host.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.\\-]", "_");
        return "__custom_csp_auto_" + slug;
    }

    /**
     * 站点扩展挂载串：通用自适应脚本 + 站点级配置覆写。
     *
     * <p>{@code code} 先执行（写 {@code window.__fmAutoConfig}），{@code js} 的
     * universal-enhance.js 随后在同一 IIFE 里读取覆写合并进 CONFIG。
     * 二期 AI 精修可以往覆写里追加 {@code episodeSelector} / {@code routes} 等
     * 任意字段，或直接追加新的 code 扩展，不必改本脚本。
     */
    static String extensionsJson(String id, String host, String name, String profile) {
        JsonObject overlay = new JsonObject();
        overlay.addProperty("name", AUTO_ID_PREFIX + slug(host));
        overlay.addProperty("host", host);
        overlay.addProperty("siteName", name);
        overlay.addProperty("profile", profile);

        JsonObject extension = new JsonObject();
        extension.addProperty("id", id);
        extension.addProperty("name", "Auto adapt · " + host);
        extension.addProperty("version", "1.0.0");
        extension.addProperty("runAt", "document-end");
        extension.addProperty("code", "window.__fmAutoConfig=" + gson().toJson(overlay) + ";");
        JsonArray js = new JsonArray();
        js.add(SCRIPT_LOCAL_URL);
        extension.add("js", js);

        JsonArray array = new JsonArray();
        array.add(extension);
        return gson().toJson(array);
    }

    // ---------- URL 工具 ----------

    /** host（小写，不含端口）。非法 URL 返回空串。 */
    public static String hostOf(String url) {
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /** 归一到站点根（{@code scheme://host/}）：粘了深链接也把主页落成站点入口。 */
    public static String originOf(String url) {
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return url.trim();
            int port = uri.getPort();
            String authority = port > 0 && port != defaultPort(scheme) ? host + ":" + port : host;
            return scheme.toLowerCase(Locale.ROOT) + "://" + authority + "/";
        } catch (Exception e) {
            return url.trim();
        }
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : "http".equalsIgnoreCase(scheme) ? 80 : -1;
    }

    private static String slug(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.\\-]", "_");
    }

    private static String md5Short(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 4; i++) builder.append(String.format(Locale.US, "%02x", bytes[i]));
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static Gson gson() {
        return new GsonBuilder().disableHtmlEscaping().create();
    }
}
