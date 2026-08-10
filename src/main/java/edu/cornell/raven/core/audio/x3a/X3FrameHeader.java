package edu.cornell.raven.core.audio.x3a;

/**
 * 20-byte big-endian X3 archive frame header ("x3" key + CRCs).
 */
public final class X3FrameHeader {

    public static final int LENGTH = 20;
    public static final int KEY = 0x7833; // "x3"
    public static final byte[] ARCHIVE_ID = {
            'X', '3', 'A', 'R', 'C', 'H', 'I', 'V'
    };

    public static final int P_KEY = 0;
    public static final int P_SOURCE_ID = 2;
    public static final int P_CHANNELS = 3;
    public static final int P_SAMPLES = 4;
    public static final int P_PAYLOAD_SIZE = 6;
    public static final int P_TIME = 8;
    public static final int P_HEADER_CRC = 16;
    public static final int P_PAYLOAD_CRC = 18;

    public final int sourceId;
    public final int channels;
    public final int samples;
    public final int payloadLen;
    public final long time;
    public final int payloadCrc;

    public X3FrameHeader(int sourceId, int channels, int samples, int payloadLen, long time, int payloadCrc) {
        this.sourceId = sourceId;
        this.channels = channels;
        this.samples = samples;
        this.payloadLen = payloadLen;
        this.time = time;
        this.payloadCrc = payloadCrc & 0xffff;
    }

    public byte[] encode() {
        byte[] h = new byte[LENGTH];
        putBe16(h, P_KEY, KEY);
        h[P_SOURCE_ID] = (byte) sourceId;
        h[P_CHANNELS] = (byte) channels;
        putBe16(h, P_SAMPLES, samples);
        putBe16(h, P_PAYLOAD_SIZE, payloadLen);
        putBe64(h, P_TIME, time);
        int headerCrc = Crc16.crc(h, 0, P_HEADER_CRC);
        putBe16(h, P_HEADER_CRC, headerCrc);
        putBe16(h, P_PAYLOAD_CRC, payloadCrc);
        return h;
    }

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

    static void putBe16(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    static void putBe64(byte[] b, int off, long v) {
        for (int i = 7; i >= 0; i--) {
            b[off + (7 - i)] = (byte) (v >>> (i * 8));
        }
    }

    static int getBe16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    static long getBe64(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xff);
        }
        return v;
    }
}
