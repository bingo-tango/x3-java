package edu.cornell.raven.x3a.internal;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// One-pass frame index over a bare `.x3a` archive, the archive-side counterpart to
/// [ChunkIndex] for `.SUD` containers.
///
/// Walks the archive's frame headers once and records, per data frame, the flat
/// `[Sample_Offset, File_Byte_Offset, Chunk_Length, Frame_Timestamp]` entry
/// [ChunkPipeline] expects — so windowed random-access decode works on archives without
/// decoding, or even reading, the payloads first. `File_Byte_Offset` points at the frame's
/// payload (past its header), so pipelines index archives with `payloadHeaderBytes = 0`.
///
/// Header CRCs are verified for every frame during the walk, which catches truncation and
/// framing corruption for the cost of 20 bytes per frame. Payload CRCs are *not* checked
/// here — that would mean touching every compressed byte at open time; callers that decode
/// the whole archive anyway (see `X3Files.decodeArchive`) validate payloads as they go.
public final class ArchiveIndex {

    private static final Pattern FS = Pattern.compile("<FS[^>]*>(\\d+)</FS>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLKLEN = Pattern.compile("<BLKLEN>(\\d+)</BLKLEN>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODES = Pattern.compile(
            "<CODES[^>]*>\\s*RICE(\\d+)\\s*,\\s*RICE(\\d+)\\s*,\\s*RICE(\\d+)\\s*,\\s*BFP\\s*</CODES>",
            Pattern.CASE_INSENSITIVE);

    /// Index-entry offset of a frame's timestamp; [ChunkPipeline] ignores it, but keeping the
    /// slot filled makes the table interchangeable with [ChunkIndex]'s.
    private static final int FRAME_TIMESTAMP = 3;

    private final long[] table;
    private final int frameCount;
    private final long totalSamples;
    private final int sampleRate;
    private final int channels;
    private final int blockLen;
    private final int[] riceOrders;
    private final String xml;

    private ArchiveIndex(long[] table, int frameCount, long totalSamples, int sampleRate, int channels,
                         int blockLen, int[] riceOrders, String xml) {
        this.table = table;
        this.frameCount = frameCount;
        this.totalSamples = totalSamples;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.blockLen = blockLen;
        this.riceOrders = riceOrders;
        this.xml = xml;
    }

    /// Indexes a mapped archive image.
    ///
    /// @param defaultBlockLen block length to assume when the config frame omits `<BLKLEN>`
    /// @param defaultRiceOrders rice orders to assume when the config frame omits `<CODES>`
    /// @throws IllegalArgumentException if the archive magic, config frame, or any frame
    ///                                  header is malformed
    public static ArchiveIndex build(MemorySegment archive, int defaultBlockLen, int[] defaultRiceOrders) {
        long size = archive.byteSize();
        if (size < X3FrameHeader.ARCHIVE_ID.length + X3FrameHeader.LENGTH) {
            throw new IllegalArgumentException("archive too small: " + size + " bytes");
        }
        for (int i = 0; i < X3FrameHeader.ARCHIVE_ID.length; i++) {
            if (archive.get(ValueLayout.JAVA_BYTE, i) != X3FrameHeader.ARCHIVE_ID[i]) {
                throw new IllegalArgumentException("missing X3ARCHIV id");
            }
        }

        long pos = X3FrameHeader.ARCHIVE_ID.length;
        X3FrameHeader xmlHdr = X3FrameHeader.decode(archive, pos);
        pos += X3FrameHeader.LENGTH;
        if (pos + xmlHdr.payloadLen > size) {
            throw new IllegalArgumentException("XML payload truncated");
        }
        if (Crc16.crc(archive, pos, xmlHdr.payloadLen) != xmlHdr.payloadCrc) {
            throw new IllegalArgumentException("XML payload CRC mismatch");
        }
        byte[] xmlBytes = archive.asSlice(pos, xmlHdr.payloadLen).toArray(ValueLayout.JAVA_BYTE);
        String xml = new String(xmlBytes, StandardCharsets.US_ASCII);
        pos += xmlHdr.payloadLen;

        long[] table = new long[16 * ChunkPipeline.INDEX_STRIDE];
        int frameCount = 0;
        long totalSamples = 0;
        int channels = 1;

        // Stop at the first non-frame boundary rather than failing: trailing padding after the
        // last frame is common, and a partially written archive should still expose what it has.
        while (pos + X3FrameHeader.LENGTH <= size) {
            if (X3FrameHeader.getBe16(archive, pos) != X3FrameHeader.KEY) {
                break;
            }
            X3FrameHeader fh = X3FrameHeader.decode(archive, pos);
            long payloadStart = pos + X3FrameHeader.LENGTH;
            if (fh.payloadLen <= 0 || payloadStart + fh.payloadLen > size) {
                break;
            }
            if (fh.samples > 0) {
                channels = Math.max(1, fh.channels);
                if ((frameCount + 1) * ChunkPipeline.INDEX_STRIDE > table.length) {
                    table = Arrays.copyOf(table, table.length * 2);
                }
                int base = frameCount * ChunkPipeline.INDEX_STRIDE;
                table[base + ChunkPipeline.SAMPLE_OFFSET] = totalSamples;
                table[base + ChunkPipeline.FILE_BYTE_OFFSET] = payloadStart;
                table[base + ChunkPipeline.CHUNK_LENGTH] = fh.payloadLen;
                table[base + FRAME_TIMESTAMP] = fh.time;
                frameCount++;
                totalSamples += fh.samples;
            }
            pos = payloadStart + fh.payloadLen;
        }

        int sampleRate = parseInt(FS, xml, 0);
        int blockLen = parseInt(BLKLEN, xml, defaultBlockLen);
        int[] rice = parseTriple(CODES, xml, defaultRiceOrders);
        return new ArchiveIndex(table, frameCount, totalSamples, sampleRate, channels, blockLen, rice, xml);
    }

    /// Flat index table in [ChunkPipeline]'s layout; only the first
    /// `frameCount() * ChunkPipeline.INDEX_STRIDE` longs are meaningful.
    public long[] table() {
        return table;
    }

    /// Number of indexed data frames.
    public int frameCount() {
        return frameCount;
    }

    /// Total frames (samples per channel) across all data frames.
    public long totalSamples() {
        return totalSamples;
    }

    /// Sample rate from the config frame's `<FS>`, or `0` if absent.
    public int sampleRate() {
        return sampleRate;
    }

    /// Channel count, taken from the first data frame header.
    public int channels() {
        return channels;
    }

    /// Coding block length from `<BLKLEN>`, or the caller's default.
    public int blockLen() {
        return blockLen;
    }

    /// Rice orders from `<CODES>`, or the caller's default.
    public int[] riceOrders() {
        return riceOrders;
    }

    /// Config-frame XML, recovered verbatim.
    public String xml() {
        return xml;
    }

    private static int parseInt(Pattern p, String xml, int def) {
        Matcher m = p.matcher(xml);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    private static int[] parseTriple(Pattern p, String xml, int[] def) {
        Matcher m = p.matcher(xml);
        if (m.find()) {
            return new int[] {
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))
            };
        }
        return def;
    }
}
