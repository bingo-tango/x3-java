package edu.cornell.raven.x3a;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class X3FrameDecoderTest {

    /// Reference vector from x3-rust `test_decode_block_ftype_2`: filter state + one rice block.
    private static final byte[] FTYPE2_VECTOR = {
            (byte) 0xf2, 0x76, (byte) 0xb1, (byte) 0x82, 0x14, (byte) 0xd0, 0x4, 0x4, 0x58, 0x18, 0x30, 0x20,
            0x69, (byte) 0x86, 0x4, (byte) 0xfc, (byte) 0xc2, (byte) 0xf8, (byte) 0xaa,
            0x7f, (byte) 0xa1, 0xa, (byte) 0xfa, (byte) 0xad, (byte) 0xbc, (byte) 0x9d, (byte) 0x8d, 0x13,
            (byte) 0xc9, 0x66, (byte) 0xea, 0x5, (byte) 0xa3, 0x63, (byte) 0x94, (byte) 0xc9, (byte) 0xf4,
            (byte) 0x88, 0x4e, (byte) 0xb3, 0x6, (byte) 0xc9, (byte) 0xdb, (byte) 0x8f, 0x70, (byte) 0x80,
            (byte) 0xb3, (byte) 0x8b, 0x6b, 0x14, (byte) 0x88, 0x5f, 0x6c, 0x2f, (byte) 0xaa, 0x5a,
            (byte) 0xae, (byte) 0xf4, 0x29, 0x46, (byte) 0xd9, 0x12, 0x43, 0x4b, 0x4f, (byte) 0xd6,
            (byte) 0xeb, 0x24, (byte) 0xa8, 0x48, (byte) 0xc6, 0x3d, 0x1a, (byte) 0xb8, 0x71, 0x72,
            (byte) 0xb5, 0x68, (byte) 0xb4, 0x5b, (byte) 0xa1, 0x7c, (byte) 0xb2, 0x48, 0x5f, 0x67,
            (byte) 0xd9, 0x1b, 0x65, 0x0
    };

    /// Expected decode of [#FTYPE2_VECTOR]: first sample is the big-endian filter state, the next
    /// 19 come from one block (rust default `block_len=20`, `samples=20`).
    private static final short[] FTYPE2_EXPECTED = {
            -3466, // 0xf276 as i16
            -3467, -3471, -3466, -3463, -3463, -3465, -3464, -3456, -3450, -3448, -3449, -3456, -3462, -3456,
            -3462, -3461, -3463, -3468, -3462
    };

    @Test
    void decodeChunkInt_matchesRustFtype2Vector() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment payload = payloadSegment(arena);
            short[] dest = new short[20];
            X3FrameDecoder decoder = new X3FrameDecoder(20, new int[] {0, 1, 3});
            int frames = decoder.decodeChunkInt(payload, 20, 1, dest, 0, false);
            assertEquals(20, frames);
            assertArrayEquals(FTYPE2_EXPECTED, dest);
        }
    }

    /// Same reference vector through the float path, which must be the int path scaled by
    /// 1/32768 exactly — no rounding of its own.
    @Test
    void decodeChunkFloat_scalesIntDecodeToUnitInterval() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment payload = payloadSegment(arena);
            short[] ints = new short[20];
            float[] floats = new float[20];
            short[] scratch = new short[20];
            X3FrameDecoder decoder = new X3FrameDecoder(20, new int[] {0, 1, 3});

            assertEquals(20, decoder.decodeChunkInt(payload, 20, 1, ints, 0, false));
            assertEquals(20, decoder.decodeChunkFloat(payload, 20, 1, floats, 0, scratch, false));

            for (int i = 0; i < 20; i++) {
                assertEquals(ints[i] / 32768.0f, floats[i], 0.0f);
            }
        }
    }

    private static MemorySegment payloadSegment(Arena arena) {
        MemorySegment payload = arena.allocate(FTYPE2_VECTOR.length);
        for (int i = 0; i < FTYPE2_VECTOR.length; i++) {
            payload.set(ValueLayout.JAVA_BYTE, i, FTYPE2_VECTOR[i]);
        }
        return payload;
    }
}
