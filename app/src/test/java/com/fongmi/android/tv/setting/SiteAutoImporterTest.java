package com.fongmi.android.tv.setting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

public class SiteAutoImporterTest {

    private static final String PIANKU_HTML = "<!DOCTYPE html><html><head>"
            + "<meta property=\"og:site_name\" content=\"片库\">"
            + "<title>片库-4K电影天堂-高清影视资源分享</title>"
            + "</head><body>"
            + "<a href=\"/vodtype/20.html\">电影</a><a href=\"/vodtype/37.html\">连续剧</a>"
            + "<a href=\"/voddetail/118681.html\">师兄太稳健</a><a href=\"/voddetail/120161.html\">兰香如故</a>"
            + "<a href=\"/voddetail/120214.html\">云雀叫天录</a><a href=\"/voddetail/35828.html\">恶缘</a>"
            + "<a href=\"/vodsearch/\">搜索</a>"
            + "</body></html>";

    private static final String SPARSE_HTML = "<!DOCTYPE html><html><head><title>我的主页</title></head>"
            + "<body><p>hello</p><a href=\"#top\">top</a></body></html>";

    @Test
    public void siteNameFromOgSiteName() {
        assertEquals("片库", SiteAutoImporter.siteName(PIANKU_HTML, "https://4k.pianku.online/"));
    }

    @Test
    public void siteNameFromTitleSegment() {
        String html = "<html><head><title>雪习萌动漫-在线观看</title></head><body></body></html>";
        assertEquals("雪习萌动漫", SiteAutoImporter.siteName(html, "https://dm.xueximeng.com/"));
    }

    @Test
    public void siteNameFallsBackToHost() {
        String html = "<html><head></head><body></body></html>";
        assertEquals("example.com", SiteAutoImporter.siteName(html, "https://example.com/"));
    }

    @Test
    public void fingerprintDetectsMaccms() {
        assertEquals("maccms", SiteAutoImporter.fingerprint(PIANKU_HTML));
        assertEquals("maccms", SiteAutoImporter.fingerprint("<html>var player_aaaa={\"url\":\"x\"};</html>"));
    }

    @Test
    public void fingerprintDetectsHalo() {
        assertEquals("halo", SiteAutoImporter.fingerprint("<html><div class=\"module-play-list\"></div></html>"));
    }

    @Test
    public void fingerprintDefaultsToGeneric() {
        assertEquals("generic", SiteAutoImporter.fingerprint("<html><body>nothing</body></html>"));
        assertEquals("generic", SiteAutoImporter.fingerprint(null));
    }

    @Test
    public void bareUrlHtmlIsLiveSite() {
        assertTrue(SiteAutoImporter.looksLikeLiveSite("https://4k.pianku.online/", PIANKU_HTML));
    }

    @Test
    public void richDetailHtmlIsLiveSite() {
        assertTrue(SiteAutoImporter.looksLikeLiveSite("https://4k.pianku.online/voddetail/118681.html", PIANKU_HTML));
    }

    @Test
    public void sparseHtmlFileIsPageSnapshot() {
        assertFalse(SiteAutoImporter.looksLikeLiveSite("https://cnb.cool/x/-/git/raw/main/homepage.html", SPARSE_HTML));
    }

    @Test
    public void errorPageIsNotLiveSite() {
        assertFalse(SiteAutoImporter.looksLikeLiveSite("https://4k.pianku.online/",
                "<!DOCTYPE html><html><head><title>Page not found</title></head><body>404</body></html>"));
    }

    @Test
    public void buildNormalizesDeepLinkToOrigin() {
        SiteAutoImporter.Spec spec = SiteAutoImporter.build("https://4k.pianku.online/voddetail/118681.html?x=1", PIANKU_HTML);
        assertEquals("https://4k.pianku.online/", spec.homePage);
        assertEquals("片库", spec.name);
        assertEquals("maccms", spec.profile);
        assertEquals("__custom_csp_auto_4k.pianku.online", spec.key);
        assertTrue(spec.id.startsWith(SiteAutoImporter.AUTO_ID_PREFIX));
    }

    @Test
    public void buildKeepsNonDefaultPort() {
        assertEquals("http://example.com:8080/", SiteAutoImporter.originOf("http://example.com:8080/a/b"));
        assertEquals("https://example.com/", SiteAutoImporter.originOf("https://example.com:443/a/b"));
    }

    @Test
    public void extensionsJsonMountsLocalScriptWithOverlay() {
        SiteAutoImporter.Spec spec = SiteAutoImporter.build("https://4k.pianku.online/", PIANKU_HTML);
        JsonArray array = JsonParser.parseString(spec.extensionsJson).getAsJsonArray();
        assertEquals(1, array.size());
        JsonObject ext = array.get(0).getAsJsonObject();
        assertEquals("document-end", ext.get("runAt").getAsString());
        assertEquals(SiteAutoImporter.SCRIPT_LOCAL_URL, ext.getAsJsonArray("js").get(0).getAsString());
        String code = ext.get("code").getAsString();
        assertTrue(code.startsWith("window.__fmAutoConfig="));
        assertTrue(code.contains("\"host\":\"4k.pianku.online\""));
        assertTrue(code.contains("\"profile\":\"maccms\""));
        assertTrue(code.contains("\"siteName\":\"片库\""));
    }

    @Test
    public void siteKeyIsStableAndSafe() {
        assertEquals("__custom_csp_auto_4k.pianku.online", SiteAutoImporter.siteKey("4K.Pianku.Online"));
        assertEquals("__custom_csp_auto_my_host_com", SiteAutoImporter.siteKey("my host/com"));
    }

    @Test
    public void classifyRoutesWebSiteToSiteKind() {
        assertEquals(CspUrlImporter.Kind.SITE,
                CspUrlImporter.classify("https://4k.pianku.online/", PIANKU_HTML));
    }

    @Test
    public void classifyKeepsSparseHtmlFileAsPage() {
        assertEquals(CspUrlImporter.Kind.PAGE,
                CspUrlImporter.classify("https://cnb.cool/x/-/git/raw/main/homepage.html", SPARSE_HTML));
    }
}
