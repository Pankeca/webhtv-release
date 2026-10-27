package com.fongmi.android.tv.download;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 合并 HLS 的 TS 分片并修正时间轴。
 *
 * <p>部分站点的播放列表会在分块边界插入一段很短的同源贴片（角标/广告），这段贴片的
 * 时间戳从 0 重新开始。直接顺序拼接成一个 .ts 后，播放器会拿最后一段的 PTS 当总时长
 * （47 分钟的剧只剩十几秒进度条），播到结尾还会因为时间戳回退报「播放失败」并反复重试。
 *
 * <p>这里按时间轴把分片分组：整段都已被主时间轴覆盖且很短的组视为贴片丢弃，其余组
 * 整体平移到主时间轴的末尾，保证 PTS/DTS/PCR 单调连续。
 */
final class TsTimeline {

    private static final int PACKET = 188;
    private static final long WRAP = 1L << 33;
    private static final long TOLERANCE = 450000; // 5 秒，容忍 B 帧/音视频交错带来的小回退
    private static final long FRAME = 3600; // 40ms，给下一段留出接续余量
    private static final long SHORT = 60 * 90000L; // 1 分钟以内且被覆盖，按贴片丢弃

    private TsTimeline() {
    }

    /** 合并分片写入 out；fMP4 等非 TS 数据不走这里。 */
    static void merge(List<File> parts, OutputStream out) throws IOException {
        int size = parts.size();
        long[] first = new long[size];
        long[] last = new long[size];
        for (int i = 0; i < size; i++) {
            long[] range = range(Files.readAllBytes(parts.get(i).toPath()));
            first[i] = range[0];
            last[i] = range[1];
        }
        List<Block> blocks = group(first, last);
        if (blocks.isEmpty()) {
            for (File part : parts) Files.copy(part.toPath(), out);
            return;
        }
        for (Block block : select(blocks)) {
            for (int i = block.start; i <= block.end; i++) {
                byte[] data = Files.readAllBytes(parts.get(i).toPath());
                if (block.shift != 0) shift(data, block.shift);
                out.write(data);
            }
        }
    }

    /** 按 PTS 连续性分组：前后跳变超过容差就另起一组。没有 PTS 的分片并入当前组。 */
    private static List<Block> group(long[] first, long[] last) {
        List<Block> blocks = new ArrayList<>();
        Block current = null;
        for (int i = 0; i < first.length; i++) {
            if (first[i] < 0) {
                if (current == null) current = new Block(i);
                current.end = i;
                continue;
            }
            if (current != null && current.hasPts && Math.abs(first[i] - current.last) <= TOLERANCE) {
                current.end = i;
                if (last[i] > current.last) current.last = last[i];
                continue;
            }
            current = new Block(i);
            current.first = first[i];
            current.last = last[i];
            current.hasPts = true;
            blocks.add(current);
        }
        return blocks;
    }

    /** 决定保留哪些组，并算出每组的平移量。 */
    private static List<Block> select(List<Block> blocks) {
        List<Block> keep = new ArrayList<>();
        long outEnd = 0;
        long mainLast = -1;
        for (Block block : blocks) {
            if (!block.hasPts) {
                // 没有 PTS 的分片无法判断位置，原样保留
                keep.add(block);
                continue;
            }
            if (mainLast < 0) {
                mainLast = block.last;
            } else if (block.last <= mainLast && block.last - block.first < SHORT) {
                // 整段都落在已播放过的范围内且很短：贴片，丢弃
                continue;
            } else {
                block.shift = outEnd - block.first;
                mainLast = Math.max(mainLast, block.last);
            }
            outEnd = block.last + block.shift + FRAME;
            keep.add(block);
        }
        return keep;
    }

    private static long[] range(byte[] data) {
        long first = -1;
        long last = -1;
        int i = 0;
        while (i + PACKET <= data.length) {
            if (data[i] != 0x47) {
                i++;
                continue;
            }
            long pts = readPts(data, i);
            if (pts >= 0) {
                if (first < 0) first = pts;
                if (pts > last) last = pts;
            }
            i += PACKET;
        }
        return new long[]{first, last};
    }

    private static long readPts(byte[] data, int start) {
        int b1 = data[start + 1] & 0xFF;
        int afc = (data[start + 3] >> 4) & 3;
        if ((b1 & 0x40) == 0 || afc == 0 || afc == 2) return -1;
        int pos = start + 4;
        if (afc == 3) pos += 1 + (data[start + 4] & 0xFF);
        if (pos + 14 > data.length || data[pos] != 0 || data[pos + 1] != 0 || data[pos + 2] != 1) return -1;
        if ((data[pos + 7] & 0x80) == 0) return -1;
        return pts(data, pos + 9);
    }

    /** 整体平移一个分片的 PTS/DTS/PCR。 */
    private static void shift(byte[] data, long delta) {
        int i = 0;
        while (i + PACKET <= data.length) {
            if (data[i] != 0x47) {
                i++;
                continue;
            }
            int start = i;
            int b1 = data[start + 1] & 0xFF;
            int afc = (data[start + 3] >> 4) & 3;
            if ((afc & 2) != 0) shiftPcr(data, start, delta);
            if ((b1 & 0x40) != 0 && afc != 0 && afc != 2) {
                int pos = start + 4;
                if (afc == 3) pos += 1 + (data[start + 4] & 0xFF);
                if (pos + 14 <= data.length && data[pos] == 0 && data[pos + 1] == 0 && data[pos + 2] == 1) {
                    int flags = data[pos + 7] & 0xFF;
                    if ((flags & 0x80) != 0) writePts(data, pos + 9, pts(data, pos + 9) + delta);
                    if ((flags & 0x40) != 0) writePts(data, pos + 14, pts(data, pos + 14) + delta);
                }
            }
            i += PACKET;
        }
    }

    private static void shiftPcr(byte[] data, int start, long delta) {
        int pos = start + 5; // adaptation_field_length
        if ((data[pos] & 0xFF) == 0 || pos + 8 > data.length) return;
        int flags = data[pos + 1] & 0xFF;
        if ((flags & 0x10) == 0) return;
        long base = ((long) (data[pos + 2] & 0xFF) << 25) | ((long) (data[pos + 3] & 0xFF) << 17) | ((long) (data[pos + 4] & 0xFF) << 9) | ((long) (data[pos + 5] & 0xFF) << 1) | (((data[pos + 6] & 0xFF) >> 7) & 1);
        base = (base + delta) & (WRAP - 1);
        data[pos + 2] = (byte) (base >> 25);
        data[pos + 3] = (byte) (base >> 17);
        data[pos + 4] = (byte) (base >> 9);
        data[pos + 5] = (byte) (base >> 1);
        data[pos + 6] = (byte) ((data[pos + 6] & 0x7F) | ((base & 1) << 7));
    }

    private static long pts(byte[] d, int pos) {
        return ((long) (d[pos] & 0x0E) << 29) | ((long) (d[pos + 1] & 0xFF) << 22) | ((long) (d[pos + 2] & 0xFE) << 14) | ((long) (d[pos + 3] & 0xFF) << 7) | (long) ((d[pos + 4] & 0xFE) >> 1);
    }

    private static void writePts(byte[] d, int pos, long value) {
        long v = value & (WRAP - 1);
        d[pos] = (byte) ((d[pos] & 0xF0) | ((v >> 30) << 1));
        d[pos + 1] = (byte) (v >> 22);
        d[pos + 2] = (byte) ((d[pos + 2] & 0x01) | ((v >> 14) & 0xFE));
        d[pos + 3] = (byte) (v >> 7);
        d[pos + 4] = (byte) ((d[pos + 4] & 0x01) | ((v << 1) & 0xFE));
    }

    private static final class Block {

        final int start;
        int end;
        long first;
        long last;
        long shift;
        boolean hasPts;

        Block(int start) {
            this.start = start;
            this.end = start;
        }
    }
}
