package com.fongmi.android.tv.download;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Response;

public class M3u8 {

    private static final Pattern ATTR = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");
    private static final Pattern MEDIA_SEQUENCE = Pattern.compile("#EXT-X-MEDIA-SEQUENCE:(\\d+)");
    private static final Pattern BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)");

    /** 一条 #EXT-X-KEY：同一分片可带不同密钥（密钥轮换）。 */
    public static final class Key {

        private final String method;
        private final String uri;
        private final byte[] iv;
        private transient byte[] bytes;

        private Key(String method, String uri, byte[] iv) {
            this.method = method;
            this.uri = uri;
            this.iv = iv;
        }

        public boolean isEncrypted() {
            return !empty(uri) && !"NONE".equalsIgnoreCase(method);
        }

        public boolean isSampleAes() {
            return method != null && method.toUpperCase(Locale.ROOT).startsWith("SAMPLE-AES");
        }

        private byte[] keyBytes(Map<String, String> headers) throws Exception {
            if (!isEncrypted()) return null;
            if (bytes == null) bytes = fetchKey(uri, headers);
            return bytes;
        }
    }

    public static final class Segment {

        private final String url;
        private final Key key;
        private final long sequence;

        private Segment(String url, Key key, long sequence) {
            this.url = url;
            this.key = key;
            this.sequence = sequence;
        }

        public String getUrl() {
            return url;
        }

        public Key getKey() {
            return key;
        }

        public long getSequence() {
            return sequence;
        }
    }

    private final List<String> maps;
    private final List<Segment> segments;
    private final boolean live;
    private final boolean fmp4;

    public M3u8(List<String> maps, List<Segment> segments, boolean live, boolean fmp4) {
        this.maps = maps;
        this.segments = segments;
        this.live = live;
        this.fmp4 = fmp4;
    }

    public List<String> getMapUrls() {
        return maps;
    }

    public List<Segment> getSegments() {
        return segments;
    }

    public boolean isLive() {
        return live;
    }

    public boolean isFmp4() {
        return fmp4;
    }

    /** fMP4 的 #EXT-X-MAP 初始化分片（ftyp/moov）不能按片段解密合并，SAMPLE-AES 一律拒绝。 */
    public boolean hasSampleAes() {
        for (Segment segment : segments) if (segment.key != null && segment.key.isSampleAes()) return true;
        return false;
    }

    public static boolean isPlaylist(String url, String body) {
        return body != null && (body.contains("#EXTM3U") || url.contains(".m3u8"));
    }

    public static M3u8 parse(String url, String body) throws Exception {
        return parse(url, body, Collections.emptyMap());
    }

    /** Parses a playlist. Returns null if body is not an m3u8 document. Resolves nested variant playlists (picks the highest bandwidth). */
    public static M3u8 parse(String url, String body, Map<String, String> headers) throws Exception {
        if (body == null || !body.contains("#EXTM3U")) return null;
        if (body.contains("#EXT-X-STREAM-INF")) {
            String variant = bestVariantUrl(body);
            if (empty(variant)) throw new Exception("empty variant playlist");
            String resolved = resolve(url, variant);
            return parse(resolved, fetchText(resolved, headers), headers);
        }
        long mediaSequence = 0;
        Matcher sequenceMatcher = MEDIA_SEQUENCE.matcher(body);
        if (sequenceMatcher.find()) {
            try {
                mediaSequence = Long.parseLong(sequenceMatcher.group(1).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        List<String> maps = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        Key key = null;
        long index = 0;
        for (String raw : body.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-KEY")) {
                key = parseKey(line.substring(line.indexOf(':') + 1), url);
            } else if (line.startsWith("#EXT-X-MAP")) {
                String uri = attributes(line.substring(line.indexOf(':') + 1)).get("URI");
                if (!empty(uri)) maps.add(resolve(url, uri));
            } else if (!line.startsWith("#")) {
                segments.add(new Segment(resolve(url, line), key, mediaSequence + index));
                index++;
            }
        }
        boolean live = !body.contains("#EXT-X-ENDLIST");
        // #EXT-X-MAP 表示分片是 fMP4 容器，合并后应按 mp4 保存
        boolean fmp4 = body.contains("#EXT-X-MAP");
        return new M3u8(maps, segments, live, fmp4);
    }

    private static Key parseKey(String value, String base) {
        Map<String, String> attributes = attributes(value);
        String method = attributes.get("METHOD");
        String uri = attributes.get("URI");
        if ("NONE".equalsIgnoreCase(method)) return new Key(method, null, null);
        if (!empty(uri)) uri = resolve(base, uri);
        byte[] iv = null;
        String hex = attributes.get("IV");
        if (!empty(hex)) {
            if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
            if (hex.length() >= 32) iv = hex(hex);
        }
        return new Key(method, uri, iv);
    }

    private static Map<String, String> attributes(String value) {
        Map<String, String> result = new HashMap<>();
        Matcher matcher = ATTR.matcher(value);
        while (matcher.find()) {
            String raw = matcher.group(2);
            if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) raw = raw.substring(1, raw.length() - 1);
            result.put(matcher.group(1), raw);
        }
        return result;
    }

    /** Master playlist 里选带宽最高的一路，下载才有意义。 */
    private static String bestVariantUrl(String body) {
        String best = null;
        long bandwidth = -1;
        String[] lines = body.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue;
            Matcher matcher = BANDWIDTH.matcher(line);
            long value = matcher.find() ? parseLong(matcher.group(1)) : 0;
            String candidate = null;
            for (int j = i + 1; j < lines.length; j++) {
                String follow = lines[j].trim();
                if (follow.isEmpty()) continue;
                if (!follow.startsWith("#")) {
                    candidate = follow;
                    break;
                }
            }
            if (candidate == null) continue;
            if (value > bandwidth) {
                bandwidth = value;
                best = candidate;
            }
        }
        return best;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String fetchText(String url) throws Exception {
        return fetchText(url, Collections.emptyMap());
    }

    public static String fetchText(String url, Map<String, String> headers) throws Exception {
        try (Response response = OkHttp.newCall(url, safe(headers)).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new Exception("HTTP " + response.code());
            return response.body().string();
        }
    }

    public static String resolve(String base, String target) {
        if (empty(target)) return target;
        if (target.startsWith("http://") || target.startsWith("https://")) return target;
        if (base == null) return target;
        if (target.startsWith("//")) return "https:" + target;
        try {
            java.net.URI uri = new java.net.URI(base);
            return uri.resolve(target).toString();
        } catch (Exception e) {
            int index = base.lastIndexOf('/');
            return index < 0 ? target : base.substring(0, index + 1) + target;
        }
    }

    public static byte[] hex(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        return data;
    }

    public static byte[] fetchKey(String url, Map<String, String> headers) throws Exception {
        try (Response response = OkHttp.newCall(url, safe(headers)).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new Exception("key HTTP " + response.code());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            response.body().byteStream().transferTo(out);
            return out.toByteArray();
        }
    }

    /** AES-128 全分片加密；IV 缺省时按 HLS 规范用分片序号（媒体序号 + 下标）大端补齐 16 字节。 */
    public static byte[] decrypt(Key key, long sequence, byte[] data, Map<String, String> headers) throws Exception {
        if (key == null || !key.isEncrypted()) return data;
        if (key.isSampleAes()) throw new Exception("SAMPLE-AES 加密暂不支持下载");
        byte[] bytes = key.keyBytes(headers);
        if (bytes == null || bytes.length == 0) return data;
        byte[] iv = key.iv != null ? key.iv : defaultIV(sequence);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(bytes, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(data);
    }

    private static byte[] defaultIV(long sequence) {
        byte[] iv = new byte[16];
        for (int i = 0; i < 8; i++) iv[15 - i] = (byte) (sequence >>> (8 * i));
        return iv;
    }

    private static boolean empty(CharSequence value) {
        return value == null || value.length() == 0;
    }

    private static Map<String, String> safe(Map<String, String> headers) {
        return headers == null ? Collections.emptyMap() : headers;
    }

    public static File tempDir(String id) {
        File dir = new File(Path.cache(), "download/" + id);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }
}
