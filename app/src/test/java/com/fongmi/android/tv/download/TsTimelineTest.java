package com.fongmi.android.tv.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** 合并 HLS 分片时，插入的短贴片要丢弃，各段 PTS 要接续成单调。 */
public class TsTimelineTest {

    @Test
    public void dropsInsertedClipBetweenBlocks() throws Exception {
        // 正文 11s，被一段 1s 的贴片（PTS 回到 0）隔开，再接正文
        byte[] merged = merge(
                segment(1_000_000, 1_090_000),
                segment(0, 90_000),
                segment(1_090_000, 1_180_000));
        // 贴片整段已被正文覆盖且远小于 1 分钟，直接丢掉；第三段接在正文结尾（+40ms）
        assertArrayEquals(new long[]{1_000_000, 1_090_000, 1_093_600, 1_183_600}, allPts(merged));
    }

    @Test
    public void keepsContinuousSegmentsUntouched() throws Exception {
        byte[] merged = merge(
                segment(1_000_000, 1_090_000),
                segment(1_090_000, 1_180_000));
        assertArrayEquals(new long[]{1_000_000, 1_090_000, 1_090_000, 1_180_000}, allPts(merged));
    }

    @Test
    public void keepsLongContainedBlockBecauseItMayBeRealContent() throws Exception {
        // 第二段虽然落在第一段范围内，但超过 1 分钟，可能是被拆开的正片，必须保留并接到后面
        byte[] merged = merge(
                segment(0, 10_000_000),
                segment(0, 6_000_000));
        assertArrayEquals(new long[]{0, 10_000_000, 10_003_600, 16_003_600}, allPts(merged));
    }

    @Test
    public void copiesNonTsDataAsIs() throws Exception {
        byte[] first = new byte[376];
        byte[] second = new byte[376];
        second[0] = 0x1A;
        byte[] merged = merge(first, second);
        assertEquals(752, merged.length);
        assertEquals(0x1A, merged[376] & 0xFF);
    }

    private static byte[] merge(byte[]... segments) throws Exception {
        File dir = Files.createTempDirectory("ts-merge").toFile();
        List<File> parts = new ArrayList<>();
        for (int i = 0; i < segments.length; i++) {
            File file = new File(dir, i + ".bin");
            Files.write(file.toPath(), segments[i]);
            parts.add(file);
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            TsTimeline.merge(parts, out);
            return out.toByteArray();
        } finally {
            File[] files = dir.listFiles();
            if (files != null) for (File file : files) file.delete();
            dir.delete();
        }
    }

    /** 生成若干个只带 PTS 的 TS 包，每包一个 PTS。 */
    private static byte[] segment(long... values) {
        byte[] data = new byte[values.length * 188];
        for (int i = 0; i < values.length; i++) {
            int start = i * 188;
            int pos = start + 4;
            data[start] = 0x47;
            data[start + 1] = 0x41;
            data[start + 2] = 0x00;
            data[start + 3] = 0x10;
            data[pos] = 0x00;
            data[pos + 1] = 0x00;
            data[pos + 2] = 0x01;
            data[pos + 3] = (byte) 0xE0;
            data[pos + 4] = 0x00;
            data[pos + 5] = 0x00;
            data[pos + 6] = (byte) 0x80;
            data[pos + 7] = (byte) 0x80;
            writePts(data, pos + 9, values[i]);
            for (int j = pos + 14; j < start + 188; j++) data[j] = (byte) 0xFF;
        }
        return data;
    }

    private static long[] allPts(byte[] data) {
        List<Long> values = new ArrayList<>();
        for (int i = 0; i + 188 <= data.length; i += 188) {
            if (data[i] != 0x47) continue;
            int pos = i + 4 + 9;
            if (pos + 5 > data.length) continue;
            values.add(((long) (data[pos] & 0x0E) << 29) | ((long) (data[pos + 1] & 0xFF) << 22) | ((long) (data[pos + 2] & 0xFE) << 14) | ((long) (data[pos + 3] & 0xFF) << 7) | (long) ((data[pos + 4] & 0xFE) >> 1));
        }
        long[] result = new long[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }

    private static void writePts(byte[] d, int pos, long value) {
        long v = value & ((1L << 33) - 1);
        d[pos] = (byte) ((d[pos] & 0xF0) | ((v >> 30) << 1));
        d[pos + 1] = (byte) (v >> 22);
        d[pos + 2] = (byte) ((d[pos + 2] & 0x01) | ((v >> 14) & 0xFE));
        d[pos + 3] = (byte) (v >> 7);
        d[pos + 4] = (byte) ((d[pos + 4] & 0x01) | ((v << 1) & 0xFE));
    }
}
