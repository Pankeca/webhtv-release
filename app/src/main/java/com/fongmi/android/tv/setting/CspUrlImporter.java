package com.fongmi.android.tv.setting;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 社区资源链接导入：把网友分享的一条链接，判断它到底是哪种物料，交给站点注入落地。
 *
 * <p>社区（cnb.cool/fish2035/ext、webhtv issues 等）分享的东西只有五种形态：
 * <ul>
 *     <li>{@link Kind#CONFIG} 站点/直播配置 JSON —— 走「识别」并入列表；</li>
 *     <li>{@link Kind#EXTENSION} WebHome 扩展（manifest JSON 或单个 .js/.css）—— 挂到某个 WebHome 站点的 extensions；</li>
 *     <li>{@link Kind#PAGE} 自制主页 HTML —— 下载到本地，作为 WebHome 站点的 homePage；</li>
 *     <li>{@link Kind#LIVE} 直播源（.m3u / 频道 txt）—— 新建直播项；</li>
 *     <li>{@link Kind#UNKNOWN} 认不出来，直接提示用户。</li>
 * </ul>
 *
 * <p>分类逻辑全部是纯字符串/JSON 运算，不碰 Android API，可以直接在单元测试里跑。
 */
public final class CspUrlImporter {

    /** 链接指向的物料类型。 */
    public enum Kind {
        CONFIG,
        EXTENSION,
        PAGE,
        LIVE,
        UNKNOWN
    }

    /** 根级字段出现任意一个，就认为这是一份站点/直播配置，而不是扩展清单。 */
    private static final String[] CONFIG_ROOT_FIELDS = {
            "sites", "lives", "items", "webHomeExtensions", "parses", "spider",
            "rules", "ads", "hosts", "doh", "flags", "wallpaper", "logo"
    };

    /** 扩展清单里常见的字段。 */
    private static final String[] EXTENSION_FIELDS = {"extensions", "js", "code", "runAt", "cspKeyRegex"};

    private CspUrlImporter() {
    }

    /** 不引 android.text.TextUtils，保证本类可以在纯 JVM 单元测试里跑。 */
    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    /**
     * 判断链接/内容属于哪一类物料。
     *
     * @param url     原始链接，只用来做后缀提示，可以为空
     * @param content 链接拉回来的正文，可以为空（为空时退化为按后缀判断）
     */
    public static Kind classify(String url, String content) {
        String body = normalize(content);
        String lowerPath = lowerPath(url);
        if (isEmpty(body)) {
            if (isScriptPath(lowerPath)) return Kind.EXTENSION;
            if (isPagePath(lowerPath)) return Kind.PAGE;
            if (isLivePath(lowerPath)) return Kind.LIVE;
            return Kind.UNKNOWN;
        }
        if (looksLikeErrorPage(body)) return Kind.UNKNOWN;
        if (looksLikeHtml(body)) return Kind.PAGE;
        JsonElement element = parseJson(body);
        if (element != null) {
            Kind json = classifyJson(element);
            if (json != null) return json;
            if (isScriptPath(lowerPath)) return Kind.EXTENSION;
            return Kind.UNKNOWN;
        }
        if (isScriptPath(lowerPath) || looksLikeScript(body)) return Kind.EXTENSION;
        if (isLivePath(lowerPath) || looksLikeLive(body)) return Kind.LIVE;
        return Kind.UNKNOWN;
    }

    /** 从链接里取一个可读名字，用于新建站点/直播项的默认名称。 */
    public static String displayName(String url) {
        String path = pathOf(url);
        if (isEmpty(path)) return "";
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        // 去掉社区常见的后缀噪音：pomo.mom -> pomo、dm.xueximeng.manifest -> dm.xueximeng
        for (String suffix : new String[]{".manifest", "-extension", ".com", ".org", ".net", ".mom", ".html", ".htm"}) {
            if (name.toLowerCase(Locale.ROOT).endsWith(suffix)) name = name.substring(0, name.length() - suffix.length());
        }
        return name.trim();
    }

    /**
     * 归一化正文：去 BOM、去 Markdown 代码围栏、去首尾空白。
     * 社区帖子里的链接内容经常被包在 ```json 里，直接解析会失败。
     */
    public static String normalize(String content) {
        if (content == null) return "";
        String value = content.replace("\uFEFF", "").trim();
        if (value.isEmpty()) return "";
        value = value.replaceAll("(?m)^\\s*```[a-zA-Z0-9_+-]*\\s*$", "");
        value = value.replaceAll("(?m)^\\s*```\\s*$", "");
        return value.trim();
    }

    /** 判断正文是不是一个 HTML 页面。只看开头一段，避免大页面全量扫描。 */
    public static boolean looksLikeHtml(String body) {
        if (isEmpty(body)) return false;
        String head = body.length() > 1024 ? body.substring(0, 1024) : body;
        String lower = head.toLowerCase(Locale.ROOT).trim();
        return lower.startsWith("<!doctype") || lower.startsWith("<html") || lower.contains("<head>") || lower.contains("<body");
    }

    /**
     * 判断正文是不是一张「404 / 页面不存在」的错误页。
     *
     * <p>这条不是洁癖：cnb.cool 的 raw 直链格式是 {@code /-/git/raw/<branch>/<path>}，
     * 写成 {@code /-/raw/...} 会返回 <b>HTTP 200 + 「Page not found」HTML</b>，
     * 不拦的话会被当成主页下载下来，变成一个垃圾站点。
     */
    public static boolean looksLikeErrorPage(String body) {
        if (!looksLikeHtml(body)) return false;
        String head = body.length() > 2048 ? body.substring(0, 2048) : body;
        String lower = head.toLowerCase(Locale.ROOT);
        return lower.contains("page not found")
                || lower.contains("<title>404")
                || lower.contains("404 not found")
                || lower.contains("error 404")
                || lower.contains("页面不存在")
                || lower.contains("找不到页面")
                || lower.contains("not found ·");
    }

    /** 判断正文是不是一段脚本（扩展 js）。 */    public static boolean looksLikeScript(String body) {
        if (isEmpty(body)) return false;
        if (body.contains("<html") || body.contains("<!DOCTYPE")) return false;
        return body.contains("function") || body.contains("=>") || body.contains("GM_") || body.startsWith("(function");
    }

    /** 判断正文是不是一份直播源（频道名,地址 或 #EXTM3U 频道清单）。 */
    public static boolean looksLikeLive(String body) {
        if (isEmpty(body)) return false;
        String[] lines = body.split("\\r?\\n");
        int matched = 0;
        for (String line : lines) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            if (isChannelLine(value)) matched++;
            if (matched >= 2) return true;
        }
        return false;
    }

    private static boolean isChannelLine(String line) {
        int comma = line.indexOf(',');
        if (comma <= 0) return false;
        String url = line.substring(comma + 1).trim();
        if (url.contains(",")) url = url.substring(0, url.indexOf(',')).trim();
        return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("rtmp://") || url.startsWith("rtsp://");
    }

    /** JSON 再细分类；返回 null 表示「像 JSON 但判断不了」。 */
    private static Kind classifyJson(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (hasAny(object, CONFIG_ROOT_FIELDS)) return Kind.CONFIG;
            if (hasAny(object, EXTENSION_FIELDS)) return Kind.EXTENSION;
            // 单个站点对象：{key/name/homePage/api} 或 {name,url,type} 的直播对象
            if (object.has("key") || object.has("api") || object.has("homePage") || object.has("site")) return Kind.CONFIG;
            if (object.has("url") || object.has("groups") || object.has("epg")) return Kind.CONFIG;
            return null;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (array.isEmpty()) return null;
            int extension = 0;
            int config = 0;
            for (JsonElement child : array) {
                if (!child.isJsonObject()) continue;
                JsonObject object = child.getAsJsonObject();
                if (hasAny(object, EXTENSION_FIELDS) && !object.has("key") && !object.has("api") && !object.has("name")) extension++;
                else config++;
            }
            if (extension > 0 && config == 0) return Kind.EXTENSION;
            if (config > 0) return Kind.CONFIG;
            return null;
        }
        return null;
    }

    private static boolean hasAny(JsonObject object, String[] keys) {
        for (String key : keys) if (object.has(key)) return true;
        return false;
    }

    private static JsonElement parseJson(String body) {
        char first = firstSignificant(body);
        if (first != '{' && first != '[') return null;
        try {
            JsonElement element = JsonParser.parseString(body);
            return element == null || element.isJsonNull() ? null : element;
        } catch (Exception e) {
            return null;
        }
    }

    private static char firstSignificant(String body) {
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (!Character.isWhitespace(c) && c != '\uFEFF') return c;
        }
        return 0;
    }

    private static boolean isScriptPath(String lowerPath) {
        return lowerPath.endsWith(".js") || lowerPath.endsWith(".mjs") || lowerPath.endsWith(".css");
    }

    private static boolean isPagePath(String lowerPath) {
        return lowerPath.endsWith(".html") || lowerPath.endsWith(".htm");
    }

    private static boolean isLivePath(String lowerPath) {
        return lowerPath.endsWith(".m3u") || lowerPath.endsWith(".txt");
    }

    /** 去掉 query / fragment，只留路径。 */
    public static String pathOf(String url) {
        if (isEmpty(url)) return "";
        String value = url.trim();
        int cut = value.length();
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        if (query >= 0) cut = Math.min(cut, query);
        if (fragment >= 0) cut = Math.min(cut, fragment);
        value = value.substring(0, cut);
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            int slash = value.indexOf('/', scheme + 3);
            value = slash >= 0 ? value.substring(slash) : "/";
        }
        return value;
    }

    private static String lowerPath(String url) {
        return pathOf(url).toLowerCase(Locale.ROOT);
    }

    /** 判断一条字符串是不是 http(s) 链接，用于剪贴板预填。 */
    public static boolean isHttpUrl(String value) {
        if (isEmpty(value)) return false;
        String text = value.trim().toLowerCase(Locale.ROOT);
        return text.startsWith("http://") || text.startsWith("https://");
    }

    /** 从一段可能混着说明文字的分享文本里，挑出第一个 http(s) 链接。 */
    public static String firstHttpUrl(String text) {
        if (isEmpty(text)) return "";
        int start = httpIndex(text);
        if (start < 0) return "";
        int end = start;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (Character.isWhitespace(c)) break;
            if ("（）()<>\"'，。、；;".indexOf(c) >= 0) break;
            end++;
        }
        return text.substring(start, end);
    }

    private static int httpIndex(String text) {
        int https = text.indexOf("https://");
        int http = text.indexOf("http://");
        if (https < 0) return http;
        if (http < 0) return https;
        return Math.min(https, http);
    }

    /**
     * 把一个扩展来源（manifest / js 的链接）追加进站点已有的 extensions 文本。
     *
     * <p>存链接而不是存内容，是为了让 {@code WebHomeExtensionRegistry} 以该链接为 base
     * 解析 manifest 里的相对路径（社区 manifest 普遍写 {@code "js": ["./xxx.js"]}）。
     */
    public static String appendExtension(String currentText, String source) {
        if (isEmpty(source)) return currentText == null ? "" : currentText;
        JsonArray array = extensionArray(currentText);
        if (containsSource(array, source)) return pretty(array);
        array.add(source);
        return pretty(array);
    }

    private static JsonArray extensionArray(String text) {
        JsonElement element = parseJson(normalize(text));
        if (element == null) return new JsonArray();
        if (element.isJsonArray()) return element.getAsJsonArray();
        JsonArray array = new JsonArray();
        if (element.isJsonObject()) {
            JsonElement nested = element.getAsJsonObject().get("extensions");
            if (nested != null && nested.isJsonArray()) return nested.getAsJsonArray();
            if (nested != null && !nested.isJsonNull()) {
                array.add(nested);
                return array;
            }
        }
        array.add(element);
        return array;
    }

    private static boolean containsSource(JsonArray array, String source) {
        for (JsonElement element : array) {
            if (element.isJsonPrimitive() && source.equals(element.getAsString())) return true;
            if (!element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            for (String key : new String[]{"manifestUrl", "manifest", "sourceUrl", "updateUrl", "url"}) {
                JsonElement value = object.get(key);
                if (value != null && value.isJsonPrimitive() && source.equals(value.getAsString())) return true;
            }
            JsonElement js = object.get("js");
            if (js != null && js.isJsonArray()) {
                for (JsonElement item : js.getAsJsonArray()) {
                    if (item.isJsonPrimitive() && source.equals(item.getAsString())) return true;
                }
            }
        }
        return false;
    }

    /** 美化输出，写入 registry 时人类可读。 */
    public static String pretty(JsonElement element) {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(element);
    }

    /** 把 manifest 里声明的 cspKeyRegex 收集出来，便于自动匹配站点（没有就返回空表）。 */
    public static List<String> cspKeys(String content) {
        List<String> keys = new ArrayList<>();
        JsonElement element = parseJson(normalize(content));
        if (element == null) return keys;
        collectCspKeys(element, keys);
        return keys;
    }

    private static void collectCspKeys(JsonElement element, List<String> keys) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectCspKeys(child, keys);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        JsonElement value = object.get("cspKeyRegex");
        if (value != null && value.isJsonArray()) {
            for (JsonElement child : value.getAsJsonArray()) {
                if (child.isJsonPrimitive() && !isEmpty(child.getAsString()) && !keys.contains(child.getAsString())) keys.add(child.getAsString());
            }
        }
        JsonElement nested = object.get("extensions");
        if (nested != null) collectCspKeys(nested, keys);
    }
}
