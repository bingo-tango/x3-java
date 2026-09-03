package edu.cornell.raven.x3a.internal;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// 20-byte big-endian frame header prefixing every frame in an X3 archive (`.x3a`) —
/// the "x3" key plus header/payload CRCs let [ArchiveIndex] detect a truncated or corrupted
/// archive before touching frame payload bytes.
public final class X3FrameHeader {

    /// Encoded header size in bytes.
    public static final int LENGTH = 20;
    /// Big-endian "x3" magic identifying a valid frame header.
    public static final int KEY = 0x7833;
    /// Magic bytes opening every `.x3a` archive, before the first frame header.
    public static final byte[] ARCHIVE_ID = {
            'X', '3', 'A', 'R', 'C', 'H', 'I', 'V'
    };

    /// Byte offset of [#KEY] within the encoded header.
    public static final int P_KEY = 0;
    /// Byte offset of the source-id field within the encoded header.
    public static final int P_SOURCE_ID = 2;
    /// Byte offset of the channel-count field within the encoded header.
    public static final int P_CHANNELS = 3;
    /// Byte offset of the sample-count field within the encoded header.
    public static final int P_SAMPLES = 4;
    /// Byte offset of the payload-length field within the encoded header.
    public static final int P_PAYLOAD_SIZE = 6;
    /// Byte offset of the timestamp field within the encoded header.
    public static final int P_TIME = 8;
    /// Byte offset of the header's own CRC, covering bytes `[0, P_HEADER_CRC)`.
    public static final int P_HEADER_CRC = 16;
    /// Byte offset of the payload CRC, checksumming the frame body that follows.
    public static final int P_PAYLOAD_CRC = 18;

    /// Frame source/stream id; `0` marks the archive's metadata (XML config) frame.
    public final int sourceId;
    /// Channel count for this frame's payload; `0` for metadata frames.
    public final int channels;
    /// Per-channel sample count for this frame's payload; `0` for metadata frames.
    public final int samples;
    /// Length in bytes of the payload following this header.
    public final int payloadLen;
    /// Frame timestamp, format defined by the caller (unused by decode-side validation).
    public final long time;
    /// CRC-16 of the payload bytes, checked against [Crc16#crc(byte[],int,int)] on decode.
    public final int payloadCrc;

    /// @param payloadCrc masked to 16 bits, since encoders may pass a raw `Crc16` result
    public X3FrameHeader(int sourceId, int channels, int samples, int payloadLen, long time, int payloadCrc) {
        this.sourceId = sourceId;
        this.channels = channels;
        this.samples = samples;
        this.payloadLen = payloadLen;
        this.time = time;
        this.payloadCrc = payloadCrc & 0xffff;
    }

    /// Encodes this header to its [#LENGTH]-byte on-disk form, computing the header CRC
    /// over the fields written so far.
    public byte[] encode() {
        byte[] h = new byte[LENGTH];
        encodeInto(h, 0);
        return h;
    }

    /// Encodes into `dest[off, off + LENGTH)` — the allocation-free form, so an encoder
    /// emitting one header per frame writes straight into its output buffer.
    ///
    /// @throws IllegalArgumentException if `dest` has no room for [#LENGTH] bytes at `off`
    public void encodeInto(byte[] dest, int off) {
        if (off < 0 || off + LENGTH > dest.length) {
            throw new IllegalArgumentException("no room for a frame header at " + off);
        }
        putBe16(dest, off + P_KEY, KEY);
        dest[off + P_SOURCE_ID] = (byte) sourceId;
        dest[off + P_CHANNELS] = (byte) channels;
        putBe16(dest, off + P_SAMPLES, samples);
        putBe16(dest, off + P_PAYLOAD_SIZE, payloadLen);
        putBe64(dest, off + P_TIME, time);
        putBe16(dest, off + P_HEADER_CRC, Crc16.crc(dest, off, P_HEADER_CRC));
        putBe16(dest, off + P_PAYLOAD_CRC, payloadCrc);
    }

    /// Decodes and validates a header at `off`, verifying [#KEY] and the header CRC so
    /// callers never proceed past a corrupted frame boundary.
    ///
    /// @throws IllegalArgumentException if truncated, the key doesn't match, or the CRC fails
    public static X3FrameHeader decode(byte[] bytes, int off) {
        if (bytes.length - off < LENGTH) {
            throw new IllegalArgumentException("frame header truncated");
        }
        int key = getBe16(bytes, off + P_KEY);
        if (key != KEY) {
            throw new IllegalArgumentException("invalid frame key: 0x" + Integer.toHexString(key));
        }
        int headerCrc = getBe16(bytes, off + P_HEADER_CRC);
        int expect = Crc16.crc(bytes, off, P_HEADER_CRC);
        if (headerCrc != expect) {
            throw new IllegalArgumentException("frame header CRC mismatch");
        }
        int sourceId = bytes[off + P_SOURCE_ID] & 0xff;
        int channels = bytes[off + P_CHANNELS] & 0xff;
        int samples = getBe16(bytes, off + P_SAMPLES);
        int payloadLen = getBe16(bytes, off + P_PAYLOAD_SIZE);
        long time = getBe64(bytes, off + P_TIME);
        int payloadCrc = getBe16(bytes, off + P_PAYLOAD_CRC);
        return new X3FrameHeader(sourceId, channels, samples, payloadLen, time, payloadCrc);
    }

    /// Mapped-memory counterpart of [#decode(byte[],int)], for indexing an archive without
    /// copying it onto the heap.
    ///
    /// @throws IllegalArgumentException if truncated, the key doesn't match, or the CRC fails
    public static X3FrameHeader decode(MemorySegment segment, long off) {
        if (segment.byteSize() - off < LENGTH) {
            throw new IllegalArgumentException("frame header truncated at " + off);
        }
        int key = getBe16(segment, off + P_KEY);
        if (key != KEY) {
            throw new IllegalArgumentException("invalid frame key at " + off + ": 0x" + Integer.toHexString(key));
        }
        int headerCrc = getBe16(segment, off + P_HEADER_CRC);
        int expect = Crc16.crc(segment, off, P_HEADER_CRC);
        if (headerCrc != expect) {
            throw new IllegalArgumentException("frame header CRC mismatch at " + off);
        }
        int sourceId = segment.get(ValueLayout.JAVA_BYTE, off + P_SOURCE_ID) & 0xff;
        int channels = segment.get(ValueLayout.JAVA_BYTE, off + P_CHANNELS) & 0xff;
        int samples = getBe16(segment, off + P_SAMPLES);
        int payloadLen = getBe16(segment, off + P_PAYLOAD_SIZE);
        long time = getBe64(segment, off + P_TIME);
        int payloadCrc = getBe16(segment, off + P_PAYLOAD_CRC);
        return new X3FrameHeader(sourceId, channels, samples, payloadLen, time, payloadCrc);
    }

    static void putBe16(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    static void putBe64(byte[] b, int off, long v) {
        for (int i = 7; i >= 0; i--) {
            b[off + (7 - i)] = (byte) (v >>> (i * 8));
        }
    }

    public static int getBe16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    static long getBe64(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xff);
        }
        return v;
    }

    public static int getBe16(MemorySegment s, long off) {
        return ((s.get(ValueLayout.JAVA_BYTE, off) & 0xff) << 8) | (s.get(ValueLayout.JAVA_BYTE, off + 1) & 0xff);
    }

    static long getBe64(MemorySegment s, long off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (s.get(ValueLayout.JAVA_BYTE, off + i) & 0xff);
        }
        return v;
    }
}
