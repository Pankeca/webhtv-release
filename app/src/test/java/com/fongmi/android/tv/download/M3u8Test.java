package com.fongmi.android.tv.download;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class M3u8Test {

    private static final String BASE = "https://cdn.example.com/show/index.m3u8";

    @Test
    public void parse_tracksPerSegmentKeysAndMediaSequence() throws Exception {
        String body = String.join("\n",
                "#EXTM3U",
                "#EXT-X-VERSION:3",
                "#EXT-X-MEDIA-SEQUENCE:42",
                "#EXT-X-KEY:METHOD=AES-128,URI=\"key1.bin\",IV=0x000102030405060708090A0B0C0D0E0F",
                "#EXTINF:4,",
                "seg1.ts",
                "#EXTINF:4,",
                "seg2.ts",
                "#EXT-X-KEY:METHOD=NONE",
                "#EXTINF:4,",
                "seg3.ts",
                "#EXT-X-ENDLIST");
        M3u8 playlist = M3u8.parse(BASE, body, null);
        assertNotNull(playlist);
        assertFalse(playlist.isLive());
        assertFalse(playlist.isFmp4());
        List<M3u8.Segment> segments = playlist.getSegments();
        assertEquals(3, segments.size());
        assertEquals("https://cdn.example.com/show/seg1.ts", segments.get(0).getUrl());
        assertEquals(42, segments.get(0).getSequence());
        assertEquals(44, segments.get(2).getSequence());
        assertNotNull(segments.get(0).getKey());
        assertTrue(segments.get(0).getKey().isEncrypted());
        assertFalse(segments.get(2).getKey().isEncrypted());
        assertFalse(playlist.hasSampleAes());
    }

    @Test
    public void parse_collectsFmp4InitSegment() throws Exception {
        String body = String.join("\n",
                "#EXTM3U",
                "#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"720@0\"",
                "#EXTINF:4,",
                "seg1.m4s",
                "#EXT-X-ENDLIST");
        M3u8 playlist = M3u8.parse(BASE, body, null);
        assertNotNull(playlist);
        assertTrue(playlist.isFmp4());
        assertEquals(Collections.singletonList("https://cdn.example.com/show/init.mp4"), playlist.getMapUrls());
        assertEquals(1, playlist.getSegments().size());
        assertNull(playlist.getSegments().get(0).getKey());
    }

    @Test
    public void parse_flagsSampleAesAsUnsupported() throws Exception {
        String body = String.join("\n",
                "#EXTM3U",
                "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\"",
                "#EXTINF:4,",
                "seg1.ts",
                "#EXT-X-ENDLIST");
        M3u8 playlist = M3u8.parse(BASE, body, null);
        assertNotNull(playlist);
        assertTrue(playlist.hasSampleAes());
    }

    @Test
    public void parse_returnsNullForNonPlaylist() throws Exception {
        assertNull(M3u8.parse(BASE, "{\"code\":0}", null));
    }
}
