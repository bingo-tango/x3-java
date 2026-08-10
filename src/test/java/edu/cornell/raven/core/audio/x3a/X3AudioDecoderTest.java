package edu.cornell.raven.core.audio.x3a;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertEquals;

class X3AudioDecoderTest {

    @Test
    void decodeChunkInt_stubWritesNothing() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment payload = arena.allocate(16);
            short[] dest = new short[64];

            X3AudioDecoder decoder = new X3AudioDecoder();
            int frames = decoder.decodeChunkInt(payload, 1, dest, 0);

            assertEquals(0, frames);
        }
    }

    @Test
    void decodeChunkFloat_stubWritesNothing() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment payload = arena.allocate(16);
            float[] dest = new float[64];
            short[] scratch = new short[64];

            X3AudioDecoder decoder = new X3AudioDecoder();
            int frames = decoder.decodeChunkFloat(payload, 1, dest, 0, scratch);

            assertEquals(0, frames);
        }
    }
}
