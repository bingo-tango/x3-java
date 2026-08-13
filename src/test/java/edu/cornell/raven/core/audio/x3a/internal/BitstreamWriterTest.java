package edu.cornell.raven.core.audio.x3a.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
