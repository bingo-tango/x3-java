package edu.cornell.raven.core.audio.x3a;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Phase 3: Variable-bit-length reader over an off-heap payload slice.
 * Tracks bit cursors in primitive registers for JIT-friendly unpacking.
 * <p>
 * Bits are consumed MSB-first within each byte (X3 / SoundTrap convention).
 * When {@code swapBytePairs} is enabled, physical bytes are read as
 * {@code index ^ 1} so little-endian on-disk 16-bit words become the big-endian
 * stream the codec expects (same pair-swap PAMGuard applies before X3 unpack).
 */
public final class BitstreamReader {

    private final MemorySegment payload;
    private final long byteLength;
    private final boolean swapBytePairs;

    private long bytePos;
    private int bitBuffer;
    private int bitsInBuffer;

    public BitstreamReader(MemorySegment payload) {
        this(payload, false);
    }

    public BitstreamReader(MemorySegment payload, boolean swapBytePairs) {
        this.payload = payload;
        this.byteLength = payload.byteSize();
        this.swapBytePairs = swapBytePairs;
        this.bytePos = 0L;
        this.bitBuffer = 0;
        this.bitsInBuffer = 0;
    }

    /**
     * Reads {@code width} bits (1..32) as an unsigned value in the low bits of the result.
     */
    public int readBits(int width) {
        if (width <= 0 || width > 32) {
            throw new IllegalArgumentException("width must be in 1..32");
        }
        ensureBits(width);
        int shift = bitsInBuffer - width;
        int mask = (width == 32) ? -1 : ((1 << width) - 1);
        int value = (bitBuffer >>> shift) & mask;
        bitsInBuffer -= width;
        if (bitsInBuffer == 0) {
            bitBuffer = 0;
        } else {
            bitBuffer &= (1 << bitsInBuffer) - 1;
        }
        return value;
    }

    /**
     * Counts and consumes consecutive zero bits (does not consume the terminating one-bit).
     */
    public int countZeroBits() {
        int count = 0;
        while (true) {
            ensureBits(1);
            int shift = bitsInBuffer - 1;
            int bit = (bitBuffer >>> shift) & 1;
            if (bit != 0) {
                return count;
            }
            bitsInBuffer--;
            if (bitsInBuffer == 0) {
                bitBuffer = 0;
            } else {
                bitBuffer &= (1 << bitsInBuffer) - 1;
            }
            count++;
        }
    }

    public boolean hasRemaining() {
        return bytePos < byteLength || bitsInBuffer > 0;
    }

    public long bytePosition() {
        return bytePos;
    }

    public int bitsBuffered() {
        return bitsInBuffer;
    }

    private void ensureBits(int width) {
        while (bitsInBuffer < width && bytePos < byteLength) {
            int next = Byte.toUnsignedInt(payload.get(ValueLayout.JAVA_BYTE, physicalIndex(bytePos)));
            bytePos++;
            bitBuffer = (bitBuffer << 8) | next;
            bitsInBuffer += 8;
        }
        if (bitsInBuffer < width) {
            throw new IllegalStateException("bitstream underrun: need " + width + " bits, have " + bitsInBuffer);
        }
    }

    private long physicalIndex(long logical) {
        if (!swapBytePairs) {
            return logical;
        }
        // Swap adjacent bytes within each 16-bit word: 0↔1, 2↔3, ...
        return logical ^ 1L;
    }
}
