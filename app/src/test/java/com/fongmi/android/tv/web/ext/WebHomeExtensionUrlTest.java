package com.fongmi.android.tv.web.ext;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 回归用例：{@link WebHomeExtension#isScriptUrl(String)} 必须按后缀判断。
 * <p>
 * 历史实现用 {@code url.contains(".js")}，而 {@code ".json"} 本身包含 {@code ".js"}，
 * 于是社区扩展的 {@code *.manifest.json} 全部被当成脚本文件：
 * 清单不会被当作 JSON 拉取，而是把 JSON 文本直接注入页面，导致整段扩展静默 SyntaxError。
 */
public class WebHomeExtensionUrlTest {

    @Test
    public void manifestJsonIsNotScript() {
        assertFalse(WebHomeExtension.isScriptUrl("https://cnb.cool/fish2035/ext/-/git/raw/main/examples/extensions/pomo/pomo.manifest.json"));
        assertFalse(WebHomeExtension.isScriptUrl("https://cnb.cool/fish2035/ext/-/git/raw/main/examples/extensions/gying/gying-extension.manifest.json"));
        assertFalse(WebHomeExtension.isScriptUrl("https://cnb.cool/fish2035/ext/-/git/raw/main/examples/extensions/ymvid/ymvid.manifest.json"));
        assertFalse(WebHomeExtension.isScriptUrl("https://cnb.cool/fish2035/ext/-/git/raw/main/examples/extensions/dm-xueximeng/dm.xueximeng.manifest.json"));
    }

    @Test
    public void manifestJsonIsRecognizedAsManifest() {
        assertTrue(WebHomeExtension.isManifestUrl("https://cnb.cool/a/b/pomo.manifest.json"));
        assertTrue(WebHomeExtension.isManifestUrl("https://cnb.cool/a/b/pomo.manifest.json?ref=main"));
        assertFalse(WebHomeExtension.isManifestUrl("https://cnb.cool/a/b/pomo.mom.js"));
    }

    @Test
    public void plainScriptIsScript() {
        assertTrue(WebHomeExtension.isScriptUrl("https://cnb.cool/fish2035/ext/-/git/raw/main/examples/extensions/pomo/pomo.mom.js"));
        assertTrue(WebHomeExtension.isScriptUrl("https://a.com/x.user.js"));
        assertTrue(WebHomeExtension.isScriptUrl("https://a.com/x.mjs"));
        assertTrue(WebHomeExtension.isScriptUrl("https://a.com/x.jsx"));
    }

    @Test
    public void localSchemeIsScript() {
        assertTrue(WebHomeExtension.isScriptUrl("file:///sdcard/ext/a.txt"));
        assertTrue(WebHomeExtension.isScriptUrl("local://ext/a.txt"));
    }

    @Test
    public void queryAndFragmentAreIgnored() {
        assertTrue(WebHomeExtension.isScriptUrl("https://a.com/x.js?v=1"));
        assertTrue(WebHomeExtension.isScriptUrl("https://a.com/x.js#frag"));
        assertFalse(WebHomeExtension.isScriptUrl("https://a.com/x.manifest.json?v=1"));
    }

    @Test
    public void falsePositiveFromSubstringIsGone() {
        // 老实现里路径中含 ".js" 子串就会误判，这些都必须为 false
        assertFalse(WebHomeExtension.isScriptUrl("https://a.com/a.json/entry"));
        assertFalse(WebHomeExtension.isScriptUrl("https://a.com/config.json"));
        assertFalse(WebHomeExtension.isScriptUrl("https://a.com/js/api"));
        assertFalse(WebHomeExtension.isScriptUrl(""));
    }
}
