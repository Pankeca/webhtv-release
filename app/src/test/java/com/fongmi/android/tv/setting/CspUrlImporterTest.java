package com.fongmi.android.tv.setting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class CspUrlImporterTest {

    private static final String MANIFEST = "{\n"
            + "  \"extensions\": [\n"
            + "    {\n"
            + "      \"id\": \"pomo-native-router\",\n"
            + "      \"name\": \"Pomo native router\",\n"
            + "      \"version\": \"1.2.2\",\n"
            + "      \"runAt\": \"document-end\",\n"
            + "      \"js\": [\"./pomo.mom.js\"]\n"
            + "    }\n"
            + "  ]\n"
            + "}";

    private static final String SITE_CONFIG = "{\n"
            + "  \"sites\": [\n"
            + "    {\"key\": \"pomo\", \"name\": \"Pomo\", \"type\": 3, \"api\": \"csp_Builtin\", \"homePage\": \"https://example.com\"}\n"
            + "  ]\n"
            + "}";

    @Test
    public void extensionManifestIsRecognized() {
        assertEquals(CspUrlImporter.Kind.EXTENSION,
                CspUrlImporter.classify("https://cnb.cool/fish2035/ext/raw/main/examples/extensions/pomo/pomo.manifest.json", MANIFEST));
    }

    @Test
    public void siteConfigIsRecognized() {
        assertEquals(CspUrlImporter.Kind.CONFIG,
                CspUrlImporter.classify("https://example.com/config.json", SITE_CONFIG));
        assertEquals(CspUrlImporter.Kind.CONFIG,
                CspUrlImporter.classify("https://example.com/raw", "[{\"key\": \"a\", \"name\": \"A\", \"api\": \"./a.js\"}]"));
        assertEquals(CspUrlImporter.Kind.CONFIG,
                CspUrlImporter.classify("https://example.com/raw", "{\"webHomeExtensions\": []}"));
    }

    @Test
    public void extensionArrayWithoutSiteFieldsIsRecognized() {
        assertEquals(CspUrlImporter.Kind.EXTENSION,
                CspUrlImporter.classify("https://example.com/raw", "[{\"id\": \"x\", \"js\": [\"./x.js\"]}]"));
    }

    @Test
    public void htmlPageIsRecognized() {
        assertEquals(CspUrlImporter.Kind.PAGE,
                CspUrlImporter.classify("https://example.com/nostr.html", "<!DOCTYPE html><html><head></head><body>hi</body></html>"));
    }

    @Test
    public void scriptIsRecognizedEvenWithoutSuffix() {
        assertEquals(CspUrlImporter.Kind.EXTENSION,
                CspUrlImporter.classify("https://example.com/raw", "(function(){ GM_addStyle('body{}'); })();"));
    }

    @Test
    public void liveListIsRecognized() {
        String body = "央视一套,http://a.example.com/1.m3u8\n央视二套,http://a.example.com/2.m3u8\n";
        assertEquals(CspUrlImporter.Kind.LIVE, CspUrlImporter.classify("https://example.com/live.txt", body));
    }

    @Test
    public void garbageIsUnknown() {
        assertEquals(CspUrlImporter.Kind.UNKNOWN, CspUrlImporter.classify("https://example.com/a", "404 not found"));
        assertEquals(CspUrlImporter.Kind.UNKNOWN, CspUrlImporter.classify("https://example.com/a", ""));
    }

    @Test
    public void normalizeStripsBomAndFences() {
        String raw = "\uFEFF```json\n" + SITE_CONFIG + "\n```\n";
        String value = CspUrlImporter.normalize(raw);
        assertTrue(value.startsWith("{"));
        assertTrue(value.endsWith("}"));
        assertEquals(CspUrlImporter.Kind.CONFIG, CspUrlImporter.classify("https://example.com/x.json", raw));
    }

    @Test
    public void displayNameDropsCommunityNoise() {
        assertEquals("pomo", CspUrlImporter.displayName("https://x/pomo/pomo.mom.js"));
        assertEquals("dm.xueximeng", CspUrlImporter.displayName("https://x/dm/dm.xueximeng.manifest.json"));
        assertEquals("gying", CspUrlImporter.displayName("https://x/gying/gying-extension.js"));
        assertEquals("nostr", CspUrlImporter.displayName("https://x/homepages/nostr.html"));
    }

    @Test
    public void cspKeysAreCollected() {
        String body = "{\"extensions\": [{\"id\": \"x\", \"cspKeyRegex\": [\"^pomo$\", \"^pomo2$\"]}]}";
        List<String> keys = CspUrlImporter.cspKeys(body);
        assertEquals(2, keys.size());
        assertEquals("^pomo$", keys.get(0));
    }

    @Test
    public void shareTextYieldsFirstUrl() {
        assertEquals("https://cnb.cool/fish2035/ext",
                CspUrlImporter.firstHttpUrl("网友自制扩展：https://cnb.cool/fish2035/ext（下载后放进去）"));
        assertEquals("https://a.example.com/x.json",
                CspUrlImporter.firstHttpUrl("链接 https://a.example.com/x.json，注意区分"));
    }

    @Test
    public void appendExtensionCreatesArrayFromEmpty() {
        String text = CspUrlImporter.appendExtension("", "https://a.example.com/pomo.manifest.json");
        assertTrue(text.trim().startsWith("["));
        assertTrue(text.contains("https://a.example.com/pomo.manifest.json"));
    }

    @Test
    public void appendExtensionKeepsExistingEntries() {
        String current = "[\"https://a.example.com/one.js\"]";
        String text = CspUrlImporter.appendExtension(current, "https://a.example.com/two.manifest.json");
        assertTrue(text.contains("https://a.example.com/one.js"));
        assertTrue(text.contains("https://a.example.com/two.manifest.json"));
    }

    @Test
    public void appendExtensionUnwrapsManifestWrapper() {
        String current = "{\"extensions\": [{\"id\": \"x\", \"js\": [\"./x.js\"]}]}";
        String text = CspUrlImporter.appendExtension(current, "https://a.example.com/y.js");
        assertTrue(text.trim().startsWith("["));
        assertTrue(text.contains("\"id\": \"x\""));
        assertTrue(text.contains("https://a.example.com/y.js"));
    }

    @Test
    public void appendExtensionIsIdempotent() {
        String url = "https://a.example.com/pomo.manifest.json";
        String once = CspUrlImporter.appendExtension("", url);
        String twice = CspUrlImporter.appendExtension(once, url);
        assertEquals(once, twice);
    }

    @Test
    public void appendExtensionDeduplicatesScriptEntries() {
        String current = "{\"id\": \"x\", \"js\": [\"https://a.example.com/y.js\"]}";
        String text = CspUrlImporter.appendExtension(current, "https://a.example.com/y.js");
        assertEquals(1, text.split("https://a.example.com/y.js", -1).length - 1);
    }
}
