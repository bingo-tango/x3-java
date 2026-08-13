package edu.cornell.raven.core.audio.x3a.internal;

import edu.cornell.raven.core.audio.x3a.X3AudioDecoder;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPipelineTest {

    @Test
    void emptyIndex_returnsZero() {
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
    void decodeWindowFloat_emptyIndex_returnsZero() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mapped = arena.allocate(256);
            ChunkPipeline pipeline = new ChunkPipeline(
                    mapped, new long[0], 0, new X3AudioDecoder(), 1, 2);

            float[] dest = new float[64];
            short[] scratch = new short[64];
            assertEquals(0, pipeline.decodeWindowFloat(0L, dest.length, dest, scratch));
        }
    }

    @Test
    void options_disableSharedLimiter() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mapped = arena.allocate(16);
            DecodeOptions opts = DecodeOptions.defaults()
                    .withMaxConcurrency(2)
                    .withSharedLimiter(false);
            ChunkPipeline pipeline = new ChunkPipeline(
                    mapped, new long[0], 0, 0L, new X3AudioDecoder(), 1, opts);
            assertFalse(pipeline.usesSharedLimiter());
            assertEquals(2, pipeline.maxConcurrency());
        }
    }

    @Test
    void options_customSharedSemaphore() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mapped = arena.allocate(16);
            Semaphore custom = new Semaphore(3);
            DecodeOptions opts = DecodeOptions.defaults().withSharedLimiter(custom);
            ChunkPipeline pipeline = new ChunkPipeline(
                    mapped, new long[0], 0, 0L, new X3AudioDecoder(), 1, opts);
            assertTrue(pipeline.usesSharedLimiter());
        }
    }
}
