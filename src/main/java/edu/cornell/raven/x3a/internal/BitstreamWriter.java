package edu.cornell.raven.x3a.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/// MSB-first variable-bit writer, mirroring [BitstreamReader]'s bit order.
///
/// Bits accumulate left-aligned in a 64-bit register and are committed to the buffer four
/// bytes at a time through a big-endian [VarHandle] — the encode-side mirror of the reader's
/// 32-bit refill window. So the per-byte work the naive shape implies (a capacity check, a
/// store, and a CRC fold for every eight bits) happens once per word instead, and crossing a
/// byte boundary is a shift rather than a recursive call.
///
/// The running CRC-16 an X3 frame header needs is still available from [#crc()] without a
/// second pass, but it is folded in batches over committed bytes rather than inside
/// [#writeBits], keeping the checksum out of the bit-packing loop.
public final class BitstreamWriter {

    /// Bits committed per [#commitWord] store.
    private static final int WORD_BITS = 32;
    /// Bytes committed per [#commitWord] store.
    private static final int WORD_BYTES = 4;

    /// Unaligned big-endian `int` stores into [#buf]; plain `set` has no alignment requirement.
    private static final VarHandle INT_BE =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);

    private byte[] buf;
    private int byteLen;

    /// Pending bits, left-aligned: the next bit written lands at position `63 - bitsInAcc`,
    /// and everything below that is zero.
    private long acc;
    /// Valid bits in [#acc]; at most `WORD_BITS - 1` on entry to [#writeBits].
    private int bitsInAcc;

    private int crc;
    /// Bytes of [#buf] already folded into [#crc].
    private int crcBytes;

    /// Starts with a 256-byte buffer, growing as needed.
    public BitstreamWriter() {
        this(256);
    }

    /// Pre-sizes the buffer to avoid growth when the frame size is known ahead of time.
    ///
    /// @param initialCapacity size hint to avoid buffer growth for known-size frames
    public BitstreamWriter(int initialCapacity) {
        // WORD_BYTES of headroom so a word commit at the very end of a pre-sized frame
        // still lands without a growth copy.
        this.buf = new byte[Math.max(16, initialCapacity) + WORD_BYTES];
        reset();
    }

    /// Rewinds to empty (including the CRC) so one writer instance can be reused across
    /// frames instead of allocating a new one each time.
    public void reset() {
        byteLen = 0;
        acc = 0L;
        bitsInAcc = 0;
        crc = Crc16.INIT;
        crcBytes = 0;
    }

    /// Writes the low `width` bits of `value` MSB-first (1..32).
    public void writeBits(int value, int width) {
        if (width <= 0 || width > 32) {
            throw new IllegalArgumentException("width must be in 1..32");
        }
        long masked = value & ((1L << width) - 1);
        // bitsInAcc <= 31 and width <= 32, so the shift distance stays in 1..63.
        acc |= masked << (64 - bitsInAcc - width);
        bitsInAcc += width;
        if (bitsInAcc >= WORD_BITS) {
            commitWord();
        }
    }

    /// Flushes any partial byte, zero-padding the low bits, without forcing even length.
    public void flush() {
        commitWholeBytes();
        if (bitsInAcc != 0) {
            ensureCapacity(byteLen + 1);
            buf[byteLen++] = (byte) (acc >>> 56);
            acc = 0L;
            bitsInAcc = 0;
        }
    }

    /// Flushes and pads to an even byte length — X3 frame payloads must be word-aligned.
    public void wordAlign() {
        flush();
        if ((byteLen & 1) != 0) {
            ensureCapacity(byteLen + 1);
            buf[byteLen++] = 0;
        }
    }

    /// Bytes committed so far; excludes any unflushed partial byte still in the accumulator.
    public int byteLength() {
        commitWholeBytes();
        return byteLen;
    }

    /// Running CRC-16 over committed bytes, for the frame header's payload CRC field.
    public int crc() {
        commitWholeBytes();
        if (crcBytes < byteLen) {
            crc = Crc16.crc(crc, buf, crcBytes, byteLen - crcBytes);
            crcBytes = byteLen;
        }
        return crc & 0xffff;
    }

    /// Snapshot of packed bytes so far (excludes an unflushed partial byte).
    public byte[] toByteArray() {
        commitWholeBytes();
        byte[] out = new byte[byteLen];
        System.arraycopy(buf, 0, out, 0, byteLen);
        return out;
    }

    /// Copies packed bytes into `dest[off..)` without an intermediate array; returns byte count.
    public int copyTo(byte[] dest, int off) {
        commitWholeBytes();
        System.arraycopy(buf, 0, dest, off, byteLen);
        return byteLen;
    }

    /// Commits the pending word: one big-endian store, one bounds check, no CRC work.
    private void commitWord() {
        if (byteLen + WORD_BYTES > buf.length) {
            grow(byteLen + WORD_BYTES);
        }
        INT_BE.set(buf, byteLen, (int) (acc >>> WORD_BITS));
        byteLen += WORD_BYTES;
        acc <<= WORD_BITS;
        bitsInAcc -= WORD_BITS;
    }

    /// Drains whole bytes out of the accumulator, leaving fewer than 8 bits pending — so the
    /// query methods observe the same "committed bytes only" state the byte-at-a-time writer
    /// exposed, without forcing the hot path to commit that often.
    private void commitWholeBytes() {
        int whole = bitsInAcc >>> 3;
        if (whole == 0) {
            return;
        }
        ensureCapacity(byteLen + whole);
        for (int i = 0; i < whole; i++) {
            buf[byteLen++] = (byte) (acc >>> 56);
            acc <<= 8;
        }
        bitsInAcc -= whole << 3;
    }

    private void ensureCapacity(int need) {
        if (need > buf.length) {
            grow(need);
        }
    }

    private void grow(int need) {
        int n = buf.length;
        while (n < need) {
            n <<= 1;
        }
        byte[] next = new byte[n];
        System.arraycopy(buf, 0, next, 0, byteLen);
        buf = next;
    }
}
