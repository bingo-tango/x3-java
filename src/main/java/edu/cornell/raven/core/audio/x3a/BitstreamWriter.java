package edu.cornell.raven.core.audio.x3a;

/**
 * MSB-first variable-bit writer (mirror of {@link BitstreamReader}).
 * Tracks a running payload CRC-16 over flushed bytes for X3 frame headers.
 */
public final class BitstreamWriter {

    private byte[] buf;
    private int byteLen;
    private int scratch;
    private int bitsInScratch;
    private int crc;

    public BitstreamWriter() {
        this(256);
    }

    public BitstreamWriter(int initialCapacity) {
        this.buf = new byte[Math.max(16, initialCapacity)];
        this.byteLen = 0;
        this.scratch = 0;
        this.bitsInScratch = 0;
        this.crc = Crc16.INIT;
    }

    public void reset() {
        byteLen = 0;
        scratch = 0;
        bitsInScratch = 0;
        crc = Crc16.INIT;
    }

    /**
     * Writes the low {@code width} bits of {@code value} MSB-first (1..32).
     */
    public void writeBits(int value, int width) {
        if (width <= 0 || width > 32) {
            throw new IllegalArgumentException("width must be in 1..32");
        }
        int mask = (width == 32) ? -1 : ((1 << width) - 1);
        value &= mask;

        int rem = 8 - bitsInScratch;
        if (width == rem) {
            scratch = (scratch << width) | value;
            flushByte();
        } else if (width < rem) {
            scratch = (scratch << width) | value;
            bitsInScratch += width;
        } else {
            int shift = width - rem;
            scratch = (scratch << rem) | (value >>> shift);
            flushByte();
            writeBits(value, shift);
        }
    }

    /**
     * Flushes any partial byte (zero-pads the low bits). Does not force even length.
     */
    public void flush() {
        if (bitsInScratch != 0) {
            scratch <<= (8 - bitsInScratch);
            flushByte();
        }
    }

    /**
     * Flushes any partial byte and pads to an even byte length (X3 word alignment).
     */
    public void wordAlign() {
        flush();
        if ((byteLen & 1) != 0) {
            scratch = 0;
            flushByte();
        }
    }

    public int byteLength() {
        return byteLen;
    }

    public int crc() {
        return crc & 0xffff;
    }

    /**
     * Snapshot of packed bytes so far (excludes unflushed scratch bits).
     */
    public byte[] toByteArray() {
        byte[] out = new byte[byteLen];
        System.arraycopy(buf, 0, out, 0, byteLen);
        return out;
    }

    /**
     * Copies packed bytes into {@code dest[off..)}; returns byte count.
     */
    public int copyTo(byte[] dest, int off) {
        System.arraycopy(buf, 0, dest, off, byteLen);
        return byteLen;
    }

    private void flushByte() {
        ensureCapacity(byteLen + 1);
        int b = scratch & 0xff;
        buf[byteLen++] = (byte) b;
        crc = Crc16.update(crc, b);
        scratch = 0;
        bitsInScratch = 0;
    }

    private void ensureCapacity(int need) {
        if (need <= buf.length) {
            return;
        }
        int n = buf.length;
        while (n < need) {
            n <<= 1;
        }
        byte[] next = new byte[n];
        System.arraycopy(buf, 0, next, 0, byteLen);
        buf = next;
    }
}
