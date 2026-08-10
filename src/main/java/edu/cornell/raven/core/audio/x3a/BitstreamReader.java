package edu.cornell.raven.core.audio.x3a;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Phase 3: Variable-bit-length reader over an off-heap payload slice.
 * Tracks bit cursors in primitive registers for JIT-friendly unpacking.
 */
public final class BitstreamReader {

    private final MemorySegment payload;
    private final long byteLength;

    private long bytePos;
    private int bitPos; // 0..7 within current byte
    private int bitBuffer;
    private int bitsInBuffer;

    public BitstreamReader(MemorySegment payload) {
        this.payload = payload;
        this.byteLength = payload.byteSize();
        this.bytePos = 0L;
        this.bitPos = 0;
        this.bitBuffer = 0;
        this.bitsInBuffer = 0;
    }

    /**
     * Reads up to 32 bits from the stream. Stub returns zero until X3 codes are implemented.
     */
    public int readBits(int width) {
        if (width <= 0 || width > 32) {
            throw new IllegalArgumentException("width must be in 1..32");
        }
        // TODO: refill bitBuffer from payload using ValueLayout.JAVA_BYTE and shift/mask.
        ensureBits(width);
        int shift = bitsInBuffer - width;
        int value = (bitBuffer >>> shift) & ((width == 32) ? -1 : ((1 << width) - 1));
        bitsInBuffer -= width;
        bitBuffer &= (bitsInBuffer == 0) ? 0 : ((1 << bitsInBuffer) - 1);
        return value;
    }

    public boolean hasRemaining() {
        return bytePos < byteLength || bitsInBuffer > 0;
    }

    public long bytePosition() {
        return bytePos;
    }

    public int bitPosition() {
        return bitPos;
    }

    private void ensureBits(int width) {
        while (bitsInBuffer < width && bytePos < byteLength) {
            int next = Byte.toUnsignedInt(payload.get(ValueLayout.JAVA_BYTE, bytePos));
            bytePos++;
            bitBuffer = (bitBuffer << 8) | next;
            bitsInBuffer += 8;
            bitPos = 0;
        }
    }
}
