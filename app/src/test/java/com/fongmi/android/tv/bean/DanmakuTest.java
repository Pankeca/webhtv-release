package com.fongmi.android.tv.bean;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 针对「弹幕搜索结果显示成代码」的回归测试：
 * 来源服务对搜索请求返回 HTML 落地页 / 纯文本错误时，兜底解析不能把响应体整包当成一条结果。
 */
public class DanmakuTest {

    private static final String HTML_PAGE = "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head><title>LogVar弹幕API</title></head>\n<body><h1>欢迎</h1></body>\n</html>";

    @Test
    public void arrayFrom_htmlPage_returnsEmpty() {
        List<Danmaku> items = Danmaku.arrayFrom(HTML_PAGE);
        assertTrue(items.isEmpty());
    }

    @Test
    public void arrayFrom_plainTextError_returnsEmpty() {
        assertTrue(Danmaku.arrayFrom("error code: 1102").isEmpty());
        assertTrue(Danmaku.arrayFrom("接口不存在").isEmpty());
        assertTrue(Danmaku.arrayFrom("Not Found 404").isEmpty());
    }

    @Test
    public void arrayFrom_url_returnsSingleResult() {
        List<Danmaku> items = Danmaku.arrayFrom("http://example.com/danmu/x.xml");
        assertEquals(1, items.size());
        assertEquals("http://example.com/danmu/x.xml", items.get(0).getUrl());
    }

    @Test
    public void arrayFrom_absolutePath_returnsSingleResult() {
        List<Danmaku> items = Danmaku.arrayFrom("/danmu/abc.xml");
        assertEquals(1, items.size());
        assertEquals("/danmu/abc.xml", items.get(0).getUrl());
    }

    @Test
    public void arrayFrom_domainPath_returnsSingleResult() {
        List<Danmaku> items = Danmaku.arrayFrom("danmu.example.com/87654321/x.json");
        assertEquals(1, items.size());
    }

    @Test
    public void arrayFrom_empty_returnsEmpty() {
        assertTrue(Danmaku.arrayFrom("").isEmpty());
        assertTrue(Danmaku.arrayFrom(null).isEmpty());
        assertTrue(Danmaku.arrayFrom("   ").isEmpty());
    }

    @Test
    public void arrayFrom_oversizedText_returnsEmpty() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append("example.com/path").append(i);
        assertTrue(Danmaku.arrayFrom(sb.toString()).isEmpty());
    }

    @Test
    public void arrayFrom_jsonPrimitiveUrl_returnsSingleResult() {
        List<Danmaku> items = Danmaku.arrayFrom("\"http://example.com/danmu/x.xml\"");
        assertEquals(1, items.size());
        assertEquals("http://example.com/danmu/x.xml", items.get(0).getUrl());
    }
}
