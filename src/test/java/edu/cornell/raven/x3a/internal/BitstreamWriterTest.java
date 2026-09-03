package edu.cornell.raven.x3a.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitstreamWriterTest {

    @Test
    void writeBits_matchesRustPackerVectors() {
        BitstreamWriter bp = new BitstreamWriter(8);
        bp.writeBits(0x0, 9);
        bp.writeBits(0x3, 2);
        bp.flush();
        assertArrayEquals(new byte[] {0x00, 0x60}, bp.toByteArray());

        bp.reset();
        bp.writeBits(0x1ff, 9);
        bp.writeBits(0x3, 2);
        bp.flush();
        assertArrayEquals(new byte[] {(byte) 0xff, (byte) 0xe0}, bp.toByteArray());

        bp.reset();
        bp.writeBits(0, 13);
        bp.writeBits(0x1ff, 9);
        bp.flush();
        assertArrayEquals(new byte[] {0x00, 0x07, (byte) 0xfc}, bp.toByteArray());
    }

    @Test
    void roundTrip_withBitstreamReader() {
        BitstreamWriter w = new BitstreamWriter();
        w.writeBits(0x1234, 16);
        w.writeBits(0x5, 3);
        w.writeBits(0x2a, 8);
        w.wordAlign();
        byte[] bytes = w.toByteArray();

        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            var seg = arena.allocate(bytes.length);
            for (int i = 0; i < bytes.length; i++) {
                seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i, bytes[i]);
            }
            BitstreamReader r = new BitstreamReader(seg, false);
            assertEquals(0x1234, r.readBits(16));
            assertEquals(0x5, r.readBits(3));
            assertEquals(0x2a, r.readBits(8));
        }
    }

    /// Bits accumulate in a 64-bit register and commit four bytes at a time, so every width
    /// (and every phase relative to that word boundary) has to read back unchanged.
    @Test
    void roundTrip_everyWidthAcrossWordBoundaries() {
        int[] widths = new int[600];
        int[] values = new int[widths.length];
        int state = 0x2468_ace0;
        for (int i = 0; i < widths.length; i++) {
            state = state * 1_103_515_245 + 12_345;
            int width = 1 + (i % 32);
            widths[i] = width;
            values[i] = (int) (state & ((1L << width) - 1));
        }

        BitstreamWriter w = new BitstreamWriter(8);
        for (int i = 0; i < widths.length; i++) {
            w.writeBits(values[i], widths[i]);
        }
        w.wordAlign();
        byte[] bytes = w.toByteArray();

        BitstreamReader r = new BitstreamReader(bytes, false);
        for (int i = 0; i < widths.length; i++) {
            assertEquals(values[i], r.readBits(widths[i]), "word " + i + " width " + widths[i]);
        }
    }

    /// [BitstreamWriter#crc()] folds committed bytes in batches; it must still equal a CRC
    /// taken over the finished payload, and repeated calls must be stable.
    @Test
    void crc_matchesWholePayloadAndIsIdempotent() {
        BitstreamWriter w = new BitstreamWriter(4);
        for (int i = 0; i < 500; i++) {
            w.writeBits(i & 0x3ff, 1 + (i % 10));
        }
        w.wordAlign();
        byte[] bytes = w.toByteArray();

        assertEquals(Crc16.crc(bytes, 0, bytes.length), w.crc());
        assertEquals(Crc16.crc(bytes, 0, bytes.length), w.crc());
    }

    /// Interleaving queries with writes must not disturb the stream: a mid-stream `crc()` or
    /// `byteLength()` drains whole bytes out of the accumulator, and the bits that follow have
    /// to land exactly where they would have otherwise.
    @Test
    void midStreamQueries_doNotPerturbTheBitstream() {
        BitstreamWriter plain = new BitstreamWriter(8);
        BitstreamWriter probed = new BitstreamWriter(8);
        int lastLength = 0;
        for (int i = 0; i < 200; i++) {
            int width = 1 + (i % 13);
            plain.writeBits(i, width);
            probed.writeBits(i, width);
            probed.crc();
            int length = probed.byteLength();
            assertTrue(length >= lastLength, "byteLength went backwards at word " + i);
            lastLength = length;
        }
        plain.wordAlign();
        probed.wordAlign();

        assertArrayEquals(plain.toByteArray(), probed.toByteArray());
        assertEquals(plain.crc(), probed.crc());
    }

    /// A fresh writer and a reset one must produce identical bytes and checksums, since the
    /// bulk encoder reuses one writer per slot across every frame of a file.
    @Test
    void reset_leavesWriterIndistinguishableFromFresh() {
        BitstreamWriter reused = new BitstreamWriter(8);
        reused.writeBits(0x7f, 7);
        reused.writeBits(0xabcd, 16);
        reused.wordAlign();
        reused.crc();
        reused.reset();

        BitstreamWriter fresh = new BitstreamWriter(8);
        for (BitstreamWriter w : new BitstreamWriter[] {reused, fresh}) {
            w.writeBits(0x123, 12);
            w.writeBits(0x5, 3);
            w.wordAlign();
        }

        assertArrayEquals(fresh.toByteArray(), reused.toByteArray());
        assertEquals(fresh.crc(), reused.crc());
    }
}
