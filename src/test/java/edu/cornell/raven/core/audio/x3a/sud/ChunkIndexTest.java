package edu.cornell.raven.core.audio.x3a.sud;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkIndexTest {

    @Test
    void build_indexesAcousticChunksOnly() {
        try (Arena arena = Arena.ofConfined()) {
            // 128-byte header skip + one acoustic chunk header/payload + one telemetry chunk
            long size = 128L + 13L + 4L + 13L + 2L;
            MemorySegment segment = arena.allocate(size);
            segment.fill((byte) 0);

            long acousticAt = 128L;
            segment.set(ValueLayout.JAVA_BYTE, acousticAt, ChunkType.ACOUSTIC_AUDIO.id());
            segment.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), acousticAt + 1, 4);
            segment.set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), acousticAt + 5, 1234L);

            long telemetryAt = acousticAt + 13L + 4L;
            segment.set(ValueLayout.JAVA_BYTE, telemetryAt, ChunkType.TELEMETRY.id());
            segment.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), telemetryAt + 1, 2);
            segment.set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), telemetryAt + 5, 5678L);

            ChunkIndex index = new ChunkIndex();
            index.build(segment);

            assertEquals(1, index.chunkCount());
            assertEquals(acousticAt, index.fileByteOffset(0));
            assertEquals(4L, index.chunkLength(0));
            assertEquals(1234L, index.frameTimestamp(0));
            assertEquals(0L, index.sampleOffset(0));
        }
    }

    @Test
    void findChunkBySample_stubReturnsNegative() {
        ChunkIndex index = new ChunkIndex();
        assertEquals(-1, index.findChunkBySample(0L));
        assertTrue(index.table().length == 0);
    }
}
