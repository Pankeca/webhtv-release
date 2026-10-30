// 来源：alantang1977/tangtv（GPL-3.0）经逐行核对后移植。
// 改动：去掉了 AdBlockStatsStore 命中统计（统计/管理 UI 属后续阶段），保留通知提示。
// 设计文档见 docs/tangtv-integration-plan.md「阶段 2：HLS 去广」。
package com.fongmi.android.tv.player.exo;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.HlsAdblockNotice;
import com.fongmi.android.tv.utils.HlsAdblockPipeline;
import com.fongmi.android.tv.utils.HlsManifestCleaner;
import com.fongmi.android.tv.api.config.HlsRuleConfig;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.crawler.SpiderDebug;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ExoHlsAdblockDataSource implements DataSource {

    private static final String TAG = "exo-hls-adblock";
    private final DataSource upstream;
    private byte[] manifest;
    private int position;

    ExoHlsAdblockDataSource(DataSource upstream) {
        this.upstream = upstream;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        long upstreamLength = upstream.open(dataSpec);
        if (!isManifestRequest(dataSpec.uri.toString(), upstream.getResponseHeaders())) return upstreamLength;
        try {
            byte[] original = readAll();
            String text = new String(original, StandardCharsets.UTF_8);
            // 结构化规则专用：旧版兜底清洗由 fongmi media3 内置的 HlsAdsParser 负责，
            // 这里不再重复做 legacy 过滤，避免双重清洗。
            List<HlsManifestCleaner.Rule> rules = Setting.isAdblock() ? HlsRuleConfig.getRules() : List.of();
            HlsAdblockPipeline.Outcome outcome = HlsAdblockPipeline.apply(
                    dataSpec.uri.toString(), text, rules, false);
            manifest = outcome.manifest().getBytes(StandardCharsets.UTF_8);
            position = 0;
            recordAndNotify(dataSpec.uri, outcome);
            if (SpiderDebug.isEnabled()) {
                SpiderDebug.log(TAG, "manifest bytes=%d rewritten=%d removed=%d structured=%s url=%s",
                        original.length, manifest.length, outcome.removedSegments(), outcome.structured(), dataSpec.uri);
            }
            return manifest.length;
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (manifest == null) return upstream.read(buffer, offset, length);
        if (position >= manifest.length) return C.RESULT_END_OF_INPUT;
        int count = Math.min(length, manifest.length - position);
        System.arraycopy(manifest, position, buffer, offset, count);
        position += count;
        return count;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return upstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        manifest = null;
        position = 0;
        upstream.close();
    }

    static boolean isManifestUrl(String url) {
        if (url == null) return false;
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        int end = url.length();
        if (query >= 0) end = Math.min(end, query);
        if (fragment >= 0) end = Math.min(end, fragment);
        return url.substring(0, end).toLowerCase(Locale.ROOT).endsWith(".m3u8");
    }

    static boolean isManifestRequest(String url, Map<String, List<String>> responseHeaders) {
        if (isManifestUrl(url) || responseHeaders == null) return isManifestUrl(url);
        for (Map.Entry<String, List<String>> header : responseHeaders.entrySet()) {
            if (header.getKey() == null || !"content-type".equalsIgnoreCase(header.getKey())) continue;
            for (String value : header.getValue()) {
                if (isHlsContentType(value)) return true;
            }
        }
        return false;
    }

    private static boolean isHlsContentType(String value) {
        if (value == null) return false;
        String type = value.toLowerCase(Locale.ROOT);
        return type.contains("application/vnd.apple.mpegurl")
                || type.contains("application/x-mpegurl")
                || type.contains("audio/mpegurl")
                || type.contains("audio/x-mpegurl");
    }

    static Result cleanForTest(String url, String manifest) {
        HlsManifestCleaner.Rule rule = HlsManifestCleaner.Rule.builder()
                .id("test-ad-path")
                .segmentUrlPatterns(List.of("(^|/)ad/"))
                .build();
        HlsAdblockPipeline.Outcome outcome = HlsAdblockPipeline.apply(
                url, manifest, List.of(rule), true);
        return new Result(outcome.manifest(), outcome.structured() || outcome.legacy(), notice(outcome));
    }

    private byte[] readAll() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = upstream.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void recordAndNotify(Uri uri, HlsAdblockPipeline.Outcome outcome) {
        if (!outcome.structured() && !outcome.legacy()) return;
        if (!HlsAdblockNotice.shouldNotify(uri.toString(), System.currentTimeMillis())) return;
        String message = notice(outcome);
        App.post(() -> Notify.show(message));
    }

    private static String notice(HlsAdblockPipeline.Outcome outcome) {
        int removed = outcome.removedSegments() > 0 ? outcome.removedSegments() : (outcome.legacy() ? 1 : 0);
        return outcome.structured() && outcome.removedDurationSec() > 0
                ? String.format(Locale.US, "已跳过 %d 个广告片段（%.1f 秒）", removed, outcome.removedDurationSec())
                : "已跳过 " + removed + " 个广告片段";
    }

    record Result(String manifest, boolean changed, String notice) {}

    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstreamFactory;

        Factory(DataSource.Factory upstreamFactory) {
            this.upstreamFactory = upstreamFactory;
        }

        @Override
        public DataSource createDataSource() {
            return new ExoHlsAdblockDataSource(upstreamFactory.createDataSource());
        }
    }
}
