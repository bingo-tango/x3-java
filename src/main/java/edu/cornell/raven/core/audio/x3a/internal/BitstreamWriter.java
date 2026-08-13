package edu.cornell.raven.core.audio.x3a.internal;

/// MSB-first variable-bit writer, mirroring [BitstreamReader]'s bit order.
///
/// Tracks a running CRC-16 over flushed bytes as encoding proceeds, so an X3 frame
/// header's payload CRC is ready via [#crc()] without a second pass over the buffer.
public final class BitstreamWriter {

    private byte[] buf;
    private int byteLen;
    private int scratch;
    private int bitsInScratch;
    private int crc;

    /// Starts with a 256-byte buffer, growing as needed.
    public BitstreamWriter() {
        this(256);
    }

    /// Pre-sizes the buffer to avoid growth when the frame size is known ahead of time.
    ///
    /// @param initialCapacity size hint to avoid buffer growth for known-size frames
    public BitstreamWriter(int initialCapacity) {
        this.buf = new byte[Math.max(16, initialCapacity)];
        this.byteLen = 0;
        this.scratch = 0;
        this.bitsInScratch = 0;
        this.crc = Crc16.INIT;
    }

    /// Rewinds to empty (including the CRC) so one writer instance can be reused across
    /// frames instead of allocating a new one each time.
    public void reset() {
        byteLen = 0;
        scratch = 0;
        bitsInScratch = 0;
        crc = Crc16.INIT;
    }

    /// Writes the low `width` bits of `value` MSB-first (1..32).
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

    /// Flushes any partial byte, zero-padding the low bits, without forcing even length.
    public void flush() {
        if (bitsInScratch != 0) {
            scratch <<= (8 - bitsInScratch);
            flushByte();
        }
    }

    /// Flushes and pads to an even byte length — X3 frame payloads must be word-aligned.
    public void wordAlign() {
        flush();
        if ((byteLen & 1) != 0) {
            scratch = 0;
            flushByte();
        }
    }

    /// Bytes flushed so far; excludes any unflushed partial byte in scratch.
    public int byteLength() {
        return byteLen;
    }

    /// Running CRC-16 over flushed bytes, for the frame header's payload CRC field.
    public int crc() {
        return crc & 0xffff;
    }

    /// Snapshot of packed bytes so far (excludes unflushed scratch bits).
    public byte[] toByteArray() {
        byte[] out = new byte[byteLen];
        System.arraycopy(buf, 0, out, 0, byteLen);
        return out;
    }

    /// Copies packed bytes into `dest[off..)` without an intermediate array; returns byte count.
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
