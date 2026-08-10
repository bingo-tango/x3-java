package edu.cornell.raven.core.audio.x3a;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkPipelineTest {

    @Test
    void decodeWindowInt_stubReturnsZero() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mapped = arena.allocate(256);
            ChunkPipeline pipeline = new ChunkPipeline(
                    mapped, new long[0], 0, new X3AudioDecoder(), 1, 4);

            short[] dest = new short[128];
            assertEquals(0, pipeline.decodeWindowInt(0L, dest.length, dest));
            assertEquals(4, pipeline.maxConcurrency());
            assertEquals(1, pipeline.channels());
        }
    }

    @Test
    void decodeWindowFloat_stubReturnsZero() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mapped = arena.allocate(256);
            ChunkPipeline pipeline = new ChunkPipeline(
                    mapped, new long[0], 0, new X3AudioDecoder(), 1, 2);

            float[] dest = new float[64];
            short[] scratch = new short[64];
            assertEquals(0, pipeline.decodeWindowFloat(0L, dest.length, dest, scratch));
        }
    }
}
