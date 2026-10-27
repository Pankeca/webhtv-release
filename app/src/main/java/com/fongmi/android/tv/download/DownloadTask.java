package com.fongmi.android.tv.download;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import okhttp3.Response;

public class DownloadTask {

    public enum State { WAITING, RUNNING, DONE, FAILED, CANCELED, MISSING }

    public interface Listener {

        void onChange(List<DownloadTask> tasks);
    }

    private String id;
    private String vodId;
    private final String siteKey;
    private final String siteName;
    private final String vodName;
    private final String vodPic;
    private final String flag;
    private final String episodeName;
    private final String episodeUrl;
    private String ext;

    private State state;
    private int progress;
    private long bytes;
    private long total;
    private long speed;
    private String error;
    private transient Future future;
    private transient int generation;
    private transient long speedTime;
    private transient long speedBytes;

    public DownloadTask() {
        this.id = String.valueOf(System.nanoTime());
        this.vodId = "";
        this.siteKey = "";
        this.siteName = "";
        this.vodName = "";
        this.vodPic = "";
        this.flag = "";
        this.episodeName = "";
        this.episodeUrl = "";
        this.ext = "";
        this.state = State.WAITING;
    }

    public DownloadTask(String siteKey, String siteName, String vodId, String vodName, String vodPic, String flag, String episodeName, String episodeUrl) {
        this.id = String.valueOf((vodName + "|" + episodeName).hashCode());
        this.siteKey = siteKey;
        this.siteName = siteName;
        this.vodId = vodId == null ? "" : vodId;
        this.vodName = vodName;
        this.vodPic = vodPic == null ? "" : vodPic;
        this.flag = flag;
        this.episodeName = episodeName;
        this.episodeUrl = episodeUrl;
        this.ext = "";
        this.state = State.WAITING;
    }

    public String toJson() {
        return App.gson().toJson(this);
    }

    public static DownloadTask fromJson(String json) {
        try {
            DownloadTask task = App.gson().fromJson(json, DownloadTask.class);
            return task == null || TextUtils.isEmpty(task.episodeUrl) ? null : task;
        } catch (Exception e) {
            return null;
        }
    }

    void setState(State value) {
        this.state = value;
    }

    void setProgressValue(int value) {
        this.progress = value;
    }

    public static String sanitize(String name) {
        return name == null ? "video" : name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    public String getId() {
        return id;
    }

    public String getSiteKey() {
        return siteKey;
    }

    public String getVodId() {
        return vodId == null ? "" : vodId;
    }

    public String getSiteName() {
        return siteName;
    }

    public String getFlag() {
        return flag;
    }

    public String getVodName() {
        return vodName;
    }

    public String getVodPic() {
        return vodPic == null ? "" : vodPic;
    }

    public String getEpisodeUrl() {
        return episodeUrl;
    }

    public String getEpisodeName() {
        return episodeName;
    }

    public String getExt() {
        return ext == null ? "" : ext;
    }

    public State getState() {
        return state;
    }

    public int getProgress() {
        return progress;
    }

    public long getBytes() {
        return bytes;
    }

    public long getSpeed() {
        return speed;
    }

    /** 预计剩余时间（毫秒）；总大小未知或不稳定时返回 0。 */
    public long getEtaMillis() {
        if (state != State.RUNNING || speed <= 0 || total <= 0) return 0;
        long remaining = total - bytes;
        return remaining <= 0 ? 0 : remaining * 1000 / speed;
    }

    public String getError() {
        return error;
    }

    public boolean isBusy() {
        return state == State.WAITING || state == State.RUNNING;
    }

    public void start() {
        speedTime = 0;
        speedBytes = 0;
        // 同步置 RUNNING，避免调度器还停在 WAITING 时多次 pump 把并发数算少
        state = State.RUNNING;
        final int gen = ++generation;
        future = Task.submitLarge(() -> run(gen));
    }

    /** 重新入队。保留已下载分片 / .part，进度从磁盘实际数据续算。 */
    public void retry() {
        if (isBusy()) return;
        error = null;
        progress = 0;
        bytes = 0;
        total = 0;
        state = State.WAITING;
    }

    /** 由管理器定时调用，用于刷新速度显示（不改变进度）。 */
    public void tickSpeed() {
        if (state == State.RUNNING) updateSpeed();
    }

    public void cancel() {
        if (state == State.DONE || state == State.FAILED || state == State.CANCELED || state == State.MISSING) return;
        state = State.CANCELED;
        generation++;
        if (future != null) future.cancel(true);
        // 保留分片和 .part，继续下载时可断点续传
        notifyChange();
    }

    /** 任务代际校验：暂停后立刻继续时，旧线程必须立刻退出，避免两个线程写同一个文件。 */
    private boolean active(int gen) {
        return generation == gen && state == State.RUNNING;
    }

    private void run(int gen) {
        File target = null;
        try {
            if (generation != gen || state == State.CANCELED) return;
            state = State.RUNNING;
            notifyChange();
            Result result = SiteApi.playerContent(siteKey, flag, episodeUrl);
            if (result == null) throw new Exception("empty play info");
            if (result.getJx() == 1) throw new Exception("解析类源暂不支持下载");
            String url = result.getPlayUrl();
            if (TextUtils.isEmpty(url)) url = result.getUrl().v();
            if (TextUtils.isEmpty(url) || !url.startsWith("http")) throw new Exception("无有效直链");
            // 站点直链依赖 Referer/UA 的情况很常见，播放器用的就是这份 header
            Map<String, String> headers = result.getHeader() == null ? new HashMap<>() : new HashMap<>(result.getHeader());
            if (isM3u8(url)) {
                M3u8 m3u8 = M3u8.parse(url, M3u8.fetchText(url, headers), headers);
                if (m3u8 == null) throw new Exception("not an m3u8 playlist");
                target = downloadM3u8(m3u8, headers, gen);
            } else {
                target = downloadDirect(url, headers, gen);
            }
            if (!active(gen)) return;
            bytes = target.length();
            if (!isPlayable(target)) throw new Exception("下载文件校验失败");
            state = State.DONE;
            progress = 100;
            speed = 0;
            notifyChange();
        } catch (Throwable e) {
            if (!active(gen)) return;
            state = State.FAILED;
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            // 校验不过的产物直接删除，避免“文件存在但打不开”被当成已下载
            if (target != null && target.exists() && !isPlayable(target)) target.delete();
            notifyChange();
        }
    }

    /** 先按扩展名当直链下载；若返回的其实是 m3u8 文本，删掉后转走 HLS 流程。 */
    private File downloadDirect(String url, Map<String, String> headers, int gen) throws Exception {
        ext = extensionOf(url);
        File file = targetFile();
        downloadFile(url, file, headers, gen);
        if (!active(gen)) return file;
        if (isPlaylistFile(file)) {
            String body = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            file.delete();
            M3u8 m3u8 = M3u8.parse(url, body, headers);
            if (m3u8 == null) throw new Exception("not an m3u8 playlist");
            return downloadM3u8(m3u8, headers, gen);
        }
        return file;
    }

    private static boolean isPlaylistFile(File file) {
        if (file == null || !file.exists() || file.length() < 7 || file.length() > 4 * 1024 * 1024) return false;
        byte[] head = new byte[16];
        try (InputStream in = new FileInputStream(file)) {
            int read = in.read(head);
            return read > 0 && new String(head, 0, read, StandardCharsets.ISO_8859_1).contains("#EXTM3U");
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean isM3u8(String url) {
        return url.toLowerCase(java.util.Locale.ROOT).contains(".m3u8");
    }

    private static final String[] KNOWN_EXT = {"mp4", "m4v", "mov", "mkv", "webm", "flv", "ts"};

    public static boolean isVideoExt(String ext) {
        if (ext == null) return false;
        for (String value : KNOWN_EXT) if (value.equalsIgnoreCase(ext)) return true;
        return false;
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        float kb = bytes / 1024f;
        if (kb < 1024) return String.format(java.util.Locale.ROOT, "%.0fKB", kb);
        float mb = kb / 1024f;
        if (mb < 1024) return String.format(java.util.Locale.ROOT, "%.1fMB", mb);
        return String.format(java.util.Locale.ROOT, "%.2fGB", mb / 1024f);
    }

    public static String formatSpeed(long bytesPerSecond) {
        return formatSize(Math.max(0, bytesPerSecond)) + "/s";
    }

    public static String formatEta(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        long hour = seconds / 3600;
        long minute = (seconds % 3600) / 60;
        long second = seconds % 60;
        if (hour > 0) return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hour, minute, second);
        return String.format(java.util.Locale.ROOT, "%d:%02d", minute, second);
    }

    /** 从直链路径推断真实扩展名，未知时按 mp4 保存。 */
    private static String extensionOf(String url) {
        String path = url;
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash) return "mp4";
        String value = path.substring(dot + 1).toLowerCase();
        for (String ext : KNOWN_EXT) if (ext.equals(value)) return value;
        return "mp4";
    }

    public static File targetFile(String vodName, String episodeName, String ext) {
        File dir = dirOf(vodName);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, sanitize(episodeName) + "." + (TextUtils.isEmpty(ext) ? "mp4" : ext));
    }

    @Nullable
    public static File findLocal(String vodName, String episodeName) {
        File dir = dirOf(vodName);
        if (!dir.isDirectory()) return null;
        String base = sanitize(episodeName);
        for (String ext : KNOWN_EXT) {
            File file = new File(dir, base + "." + ext);
            if (file.exists() && file.length() > 0) return file;
        }
        return null;
    }

    /** 删除某集在本地所有可能的产物（含遗留 .ts、.part）。 */
    public static void deleteLocal(String vodName, String episodeName) {
        File dir = dirOf(vodName);
        if (!dir.isDirectory()) return;
        String base = sanitize(episodeName);
        for (String ext : KNOWN_EXT) {
            new File(dir, base + "." + ext).delete();
            new File(dir, base + "." + ext + ".part").delete();
        }
    }

    public static File rootDir() {
        return new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "webhtv");
    }

    private static File dirOf(String vodName) {
        return new File(rootDir(), sanitize(vodName));
    }

    public File targetFile() {
        return targetFile(vodName, episodeName, getExt());
    }

    /**
     * 简易可播性校验：TS 以 0x47 同步字节开头，MP4/fMP4 前几十字节内应出现 ftyp/styp 盒子。
     * 用于挡住半截合并文件、错误页 HTML 等“看着存在其实不能播”的产物。
     */
    public static boolean isPlayable(File file) {
        if (file == null || !file.exists() || file.length() < 1024) return false;
        byte[] head = new byte[64];
        try (InputStream in = new FileInputStream(file)) {
            int read = in.read(head);
            if (read <= 0) return false;
            if ((head[0] & 0xFF) == 0x47) return true;
            if (read >= 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45 && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3) return true;
            if (read >= 3 && head[0] == 'F' && head[1] == 'L' && head[2] == 'V') return true;
            String text = new String(head, 0, read, StandardCharsets.ISO_8859_1);
            return text.contains("ftyp") || text.contains("styp");
        } catch (Throwable e) {
            return false;
        }
    }

    private void downloadFile(String url, File target, Map<String, String> headers, int gen) throws Exception {
        File temp = new File(target.getAbsolutePath() + ".part");
        long existing = temp.exists() ? temp.length() : 0;
        Map<String, String> request = headers == null ? new HashMap<>() : new HashMap<>(headers);
        if (existing > 0) request.put("Range", "bytes=" + existing + "-");
        try (Response response = OkHttp.newCall(url, request).execute()) {
            if (response.code() == 416 && existing > 0) {
                // 服务端认为区间无效：丢弃旧 .part，从头再下
                temp.delete();
                downloadFile(url, target, headers, gen);
                return;
            }
            if (!response.isSuccessful() || response.body() == null) throw new Exception("HTTP " + response.code());
            // 只有服务端确实返回 206 才追加，否则从头覆盖，避免拼出损坏文件
            boolean append = existing > 0 && response.code() == 206;
            long offset = append ? existing : 0;
            long total = response.body().contentLength();
            if (total > 0) total += offset;
            this.total = total;
            try (OutputStream out = new FileOutputStream(temp, append)) {
                byte[] buffer = new byte[65536];
                long done = offset;
                int read;
                InputStream in = response.body().byteStream();
                while ((read = in.read(buffer)) != -1) {
                    if (!active(gen)) return;
                    out.write(buffer, 0, read);
                    done += read;
                    bytes = done;
                    if (total > 0) setProgress((int) (done * 100 / total));
                    updateSpeed();
                }
            }
        }
        Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private File downloadM3u8(M3u8 m3u8, Map<String, String> headers, int gen) throws Exception {
        if (m3u8.isLive()) throw new Exception("直播流不支持下载");
        if (m3u8.hasSampleAes()) throw new Exception("SAMPLE-AES 加密暂不支持下载");
        List<M3u8.Segment> segments = m3u8.getSegments();
        if (segments.isEmpty()) throw new Exception("empty segments");
        ext = m3u8.isFmp4() ? "mp4" : "ts";
        File target = targetFile();
        File dir = M3u8.tempDir(id);
        List<String> maps = m3u8.getMapUrls();
        int totalParts = maps.size() + segments.size();
        int completed = 0;
        long done = 0;
        for (int i = 0; i < maps.size(); i++) {
            File init = initFile(dir, i);
            if (init.exists() && init.length() > 0) {
                completed++;
                done += init.length();
            }
        }
        for (int i = 0; i < segments.size(); i++) {
            File part = partFile(dir, i);
            if (part.exists() && part.length() > 0) {
                completed++;
                done += part.length();
            }
        }
        // 续传时按磁盘上已有分片恢复进度
        bytes = done;
        total = completed > 0 ? done * totalParts / completed : 0;
        setProgress(completed * 90 / totalParts);
        // fMP4 的初始化分片（ftyp/moov）必须排在最前，否则合并出来的文件无法播放
        for (int i = 0; i < maps.size(); i++) {
            if (!active(gen)) return target;
            File init = initFile(dir, i);
            if (init.exists() && init.length() > 0) continue;
            Files.write(init.toPath(), fetchBytes(maps.get(i), headers, null, gen));
            completed++;
            done += init.length();
            bytes = done;
            setProgress(completed * 90 / totalParts);
        }
        for (int i = 0; i < segments.size(); i++) {
            if (!active(gen)) return target;
            File part = partFile(dir, i);
            if (part.exists() && part.length() > 0) continue;
            M3u8.Segment segment = segments.get(i);
            Files.write(part.toPath(), fetchBytes(segment.getUrl(), headers, segment, gen));
            completed++;
            done += part.length();
            bytes = done;
            total = completed > 0 ? bytes * totalParts / completed : 0;
            setProgress(completed * 90 / totalParts);
        }
        // 先写临时文件再改名：合并中途被杀不会留下半截文件被误当成"已下载"
        if (!active(gen)) return target;
        File temp = new File(target.getAbsolutePath() + ".part");
        try (OutputStream out = new FileOutputStream(temp)) {
            for (int i = 0; i < maps.size(); i++) Files.copy(initFile(dir, i).toPath(), out);
            List<File> parts = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) parts.add(partFile(dir, i));
            // TS 分块边界会插入时间戳回到 0 的短贴片，合并时按时间轴分组处理
            if (m3u8.isFmp4()) {
                for (File part : parts) Files.copy(part.toPath(), out);
            } else {
                TsTimeline.merge(parts, out);
            }
        }
        Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        File[] parts = dir.listFiles();
        if (parts != null) for (File part : parts) part.delete();
        dir.delete();
        return target;
    }

    private static File partFile(File dir, int index) {
        return new File(dir, String.format(java.util.Locale.ROOT, "%06d.bin", index));
    }

    private static File initFile(File dir, int index) {
        return new File(dir, String.format(java.util.Locale.ROOT, "init-%03d.bin", index));
    }

    private byte[] fetchBytes(String url, Map<String, String> headers, M3u8.Segment segment, int gen) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (!active(gen)) throw new Exception("canceled");
            try (Response response = OkHttp.newCall(url, headers == null ? new HashMap<String, String>() : headers).execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new Exception("HTTP " + response.code());
                byte[] data = response.body().bytes();
                return segment == null ? data : M3u8.decrypt(segment.getKey(), segment.getSequence(), data, headers);
            } catch (Exception e) {
                last = e;
                if (!active(gen)) throw new Exception("canceled");
                Thread.sleep(1000L * (attempt + 1));
            }
        }
        throw last != null ? last : new Exception("segment failed");
    }

    private void setProgress(int value) {
        int bounded = Math.max(0, Math.min(100, value));
        if (bounded == progress) return;
        progress = bounded;
        updateSpeed();
        App.post(() -> DownloadManager.get().emitLight());
    }

    private void updateSpeed() {
        long now = System.currentTimeMillis();
        if (speedTime == 0) {
            speedTime = now;
            speedBytes = bytes;
            return;
        }
        long delta = now - speedTime;
        if (delta < 500) return;
        speed = Math.max(0, (bytes - speedBytes) * 1000 / delta);
        speedTime = now;
        speedBytes = bytes;
    }

    private void notifyChange() {
        App.post(() -> DownloadManager.get().emit());
    }

    @Nullable
    public String debugName() {
        return vodName + " " + episodeName;
    }
}
