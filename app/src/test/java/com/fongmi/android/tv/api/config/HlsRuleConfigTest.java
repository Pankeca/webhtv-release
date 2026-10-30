// 来源：alantang1977/tangtv（GPL-3.0）经逐行核对后移植。
// 改动：删去了依赖 UserAdRule / RuleHitRecord / AdBlockStatsStore / 用户规则存储的两个用例，
//       这部分属于阶段 4（规则管理 UI + 命中统计），届时随相关类一并补回。
package com.fongmi.android.tv.api.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.bean.Rule;
import com.fongmi.android.tv.utils.HlsAdblockPipeline;
import com.fongmi.android.tv.utils.HlsManifestCleaner;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class HlsRuleConfigTest {

    @Test
    public void legacyHostsScopePlaylistAndExcludeMatchesAdSegmentUrl() {
        List<HlsManifestCleaner.Rule> compiled = new ArrayList<>();
        HlsRuleConfig.compileLegacyRules(
                List.of(Rule.create("优质", List.of(".vvvip-plays33.cc"), List.of(), List.of("16.4666"))),
                compiled);
        String manifest = manifest();

        HlsAdblockPipeline.Outcome matched = HlsAdblockPipeline.apply(
                "https://foo.vvvip-plays33.cc/index.m3u8", manifest, compiled, false);
        HlsAdblockPipeline.Outcome outsideHost = HlsAdblockPipeline.apply(
                "https://video.example.com/index.m3u8", manifest, compiled, false);

        assertTrue(matched.structured());
        assertFalse(matched.manifest().contains("16.4666-ad.ts"));
        assertFalse(outsideHost.structured());
        assertTrue(outsideHost.manifest().contains("16.4666-ad.ts"));
    }

    @Test
    public void coreProxyUrlIsExcludedFromCleaning() {
        // 本地代理自身输出的 /m3u8 清单不做二次清洗，避免重复处理。
        assertFalse(HlsAdblockPipeline.isCoreM3u8Proxy("https://example.com/index.m3u8"));
        assertTrue(HlsAdblockPipeline.isCoreM3u8Proxy("http://127.0.0.1:9978/m3u8?url=x"));
    }

    private static String manifest() {
        return "#EXTM3U\n"
                + "#EXTINF:7.0,\nhttps://cdn.example.com/16.4666-ad.ts\n"
                + "#EXTINF:8.0,\nhttps://cdn.example.com/main-1.ts\n"
                + "#EXTINF:8.0,\nhttps://cdn.example.com/main-2.ts\n"
                + "#EXT-X-ENDLIST\n";
    }
}
