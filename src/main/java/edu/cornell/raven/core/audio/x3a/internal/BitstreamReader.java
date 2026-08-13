package edu.cornell.raven.core.audio.x3a.internal;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/// MSB-first variable-bit reader over an X3 payload, shared by the SUD and archive
/// decode paths.
///
/// When `swapBytePairs` is set, physical bytes are read pair-swapped (`index ^ 1`) so
/// little-endian on-disk 16-bit words become the big-endian stream the codec expects —
/// the same swap PAMGuard applies before X3 unpack.
///
/// Tracks the cursor as a left-aligned 32-bit window refilled 4 bytes at a time rather
/// than bit-by-bit, so [Integer#numberOfLeadingZeros(int)] can measure a rice unary run
/// without looping. Prefer the `byte[]` constructors for heap archives (no FFM call per
/// byte); [MemorySegment] is for mmap SUD payloads.
public final class BitstreamReader {

    private static final int BIT_LEN = 32;
    private static final int BYTES_PER_WORD = 4;

    /// Heap payload (preferred for `.x3a`); null when using [#seg].
    private final byte[] heap;
    private final int heapBase;
    private final MemorySegment seg;
    private final long byteLength;
    private final boolean swapBytePairs;

    /// Next logical byte index to load into the window.
    private long bytePos;
    /// Left-aligned bit window (valid bits in the high [#remBit] positions).
    private int leadingWord;
    /// Number of valid bits remaining in [#leadingWord].
    private int remBit;

    /// Reads a mapped SUD/archive payload with no byte-pair swap.
    public BitstreamReader(MemorySegment payload) {
        this(payload, false);
    }

    /// Reads a mapped payload, optionally undoing SUD's byte-pair swap.
    ///
    /// @param swapBytePairs true for SUD payloads, whose on-disk 16-bit words are byte-pair swapped
    public BitstreamReader(MemorySegment payload, boolean swapBytePairs) {
        this.heap = null;
        this.heapBase = 0;
        this.seg = payload;
        this.byteLength = payload.byteSize();
        this.swapBytePairs = swapBytePairs;
        this.bytePos = 0L;
        loadNextWord();
    }

    /// Zero-copy reader over a heap byte range — the fast path for archive frame bodies,
    /// avoiding an FFM call per byte.
    public BitstreamReader(byte[] payload, int offset, int length, boolean swapBytePairs) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (offset < 0 || length < 0 || offset + length > payload.length) {
            throw new IndexOutOfBoundsException("offset/length out of range");
        }
        this.heap = payload;
        this.heapBase = offset;
        this.seg = null;
        this.byteLength = length;
        this.swapBytePairs = swapBytePairs;
        this.bytePos = 0L;
        loadNextWord();
    }

    /// Convenience for a whole-array payload; see [#BitstreamReader(byte[],int,int,boolean)].
    public BitstreamReader(byte[] payload, boolean swapBytePairs) {
        this(payload, 0, payload.length, swapBytePairs);
    }

    /// Reads `width` bits (1..32) as an unsigned value in the low bits of the result.
    public int readBits(int width) {
        if (width <= 0 || width > 32) {
            throw new IllegalArgumentException("width must be in 1..32");
        }
        if (width <= remBit) {
            int result = leadingWord >>> (BIT_LEN - width);
            incBits(width);
            return result;
        }
        int rem = width - remBit;
        int result = leadingWord >>> (BIT_LEN - width);
        incBits(remBit);
        if (remBit < rem) {
            throw underrun(width);
        }
        result |= leadingWord >>> (BIT_LEN - rem);
        incBits(rem);
        return result;
    }

    /// Counts and consumes the unary prefix of a rice code — consecutive zero bits, not
    /// including the terminating one-bit — spanning multiple 32-bit windows if needed.
    public int countZeroBits() {
        if (remBit == 0 && bytePos >= byteLength) {
            throw underrun(1);
        }
        int count = 0;
        while (true) {
            int z = Integer.numberOfLeadingZeros(leadingWord);
            if (z > remBit) {
                // Only the high remBit bits are live; low padding zeros must not count.
                count += remBit;
                if (bytePos >= byteLength) {
                    leadingWord = 0;
                    remBit = 0;
                    return count;
                }
                loadNextWord();
                continue;
            }
            count += z;
            incBits(z);
            return count;
        }
    }

    /// True while any bits remain, buffered or unread.
    public boolean hasRemaining() {
        return remBit > 0 || bytePos < byteLength;
    }

    /// Byte offset of the next unread bit, correcting for bits already loaded into the
    /// window but not yet consumed — lets callers report a decode cursor mid-window.
    public long bytePosition() {
        long loaded = bytePos;
        int unusedBytesInWindow = remBit >> 3;
        return loaded - unusedBytesInWindow - ((remBit & 7) != 0 ? 1 : 0);
    }

    /// Bits currently buffered in the window; exposed for tests exercising window refill.
    public int bitsBuffered() {
        return remBit;
    }

    private void incBits(int n) {
        if (n <= 0) {
            if (n == 0 && remBit == 0) {
                loadNextWord();
            }
            return;
        }
        if (n < remBit) {
            leadingWord <<= n;
            remBit -= n;
        } else if (n > remBit) {
            int rem = n - remBit;
            loadNextWord();
            if (remBit < rem) {
                throw underrun(n);
            }
            leadingWord <<= rem;
            remBit -= rem;
        } else {
            loadNextWord();
        }
    }

    private void loadNextWord() {
        long remaining = byteLength - bytePos;
        if (remaining <= 0) {
            leadingWord = 0;
            remBit = 0;
            return;
        }
        if (remaining >= BYTES_PER_WORD) {
            long p = bytePos;
            int b0 = u8(p);
            int b1 = u8(p + 1);
            int b2 = u8(p + 2);
            int b3 = u8(p + 3);
            leadingWord = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
            bytePos = p + BYTES_PER_WORD;
            remBit = BIT_LEN;
            return;
        }
        int n = (int) remaining;
        int word = 0;
        long p = bytePos;
        for (int i = 0; i < n; i++) {
            word |= u8(p + i) << (24 - (i << 3));
        }
        leadingWord = word;
        bytePos = p + n;
        remBit = n << 3;
    }

    private int u8(long logical) {
        long phys = swapBytePairs ? (logical ^ 1L) : logical;
        if (heap != null) {
            return heap[heapBase + (int) phys] & 0xff;
        }
        return Byte.toUnsignedInt(seg.get(ValueLayout.JAVA_BYTE, phys));
    }

    private static IllegalStateException underrun(int need) {
        return new IllegalStateException("bitstream underrun: need " + need + " bits");
    }
}
