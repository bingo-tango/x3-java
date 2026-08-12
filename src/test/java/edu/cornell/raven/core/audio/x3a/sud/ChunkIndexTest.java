package edu.cornell.raven.core.audio.x3a.sud;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkIndexTest {

    private static final ValueLayout.OfShort LE_SHORT =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final int SAMPLE_RATE = 48_000;

    /**
     * Writes one real 20-byte record header (see {@link RecordHeader}) plus a
     * zero-filled payload of {@code payloadLength} bytes, returning the offset of
     * the next record.
     */
    private static long writeRecord(MemorySegment segment, long pos, int payloadLength, int sampleCountOrRecordType) {
        segment.set(ValueLayout.JAVA_BYTE, pos, (byte) 0x52);
        segment.set(ValueLayout.JAVA_BYTE, pos + 1, (byte) 0xA9);
        segment.set(LE_SHORT, pos + 2, (short) 0); // reserved0
        segment.set(LE_SHORT, pos + 4, (short) payloadLength);
        segment.set(LE_SHORT, pos + 6, (short) sampleCountOrRecordType);
        // opaqueTail (sessionId/sequence/recordTag) left zero-filled; not needed for indexing.
        return pos + RecordHeader.BYTES + payloadLength;
    }

    @Test
    void build_indexesAudioChunksAndSkipsMetadata() {
        try (Arena arena = Arena.ofConfined()) {
            long size = (RecordHeader.BYTES + 4) * 2 + (RecordHeader.BYTES + 100) + (RecordHeader.BYTES + 120);
            MemorySegment segment = arena.allocate(size);
            segment.fill((byte) 0);

            long pos = 0;
            pos = writeRecord(segment, pos, 4, RecordHeader.METADATA_RECORD_TYPE); // leading metadata, skipped
            long chunk0At = pos;
            pos = writeRecord(segment, pos, 100, 32); // audio chunk: 32 decoded samples
            long chunk1At = pos;
            pos = writeRecord(segment, pos, 120, 48); // audio chunk: 48 decoded samples
            writeRecord(segment, pos, 4, RecordHeader.METADATA_RECORD_TYPE); // trailing metadata, skipped

            ChunkIndex index = new ChunkIndex();
            index.build(segment, SAMPLE_RATE);

            assertEquals(2, index.chunkCount());
            assertEquals(80L, index.totalSamples());

            assertEquals(0L, index.sampleOffset(0));
            assertEquals(chunk0At, index.fileByteOffset(0));
            assertEquals(100L, index.chunkLength(0));
            assertEquals(0L, index.frameTimestamp(0));

            assertEquals(32L, index.sampleOffset(1));
            assertEquals(chunk1At, index.fileByteOffset(1));
            assertEquals(120L, index.chunkLength(1));
            assertEquals(32L * 1_000_000_000L / SAMPLE_RATE, index.frameTimestamp(1));
        }
    }

    @Test
    void findChunkBySample_locatesContainingChunkAndRejectsOutOfRange() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate((RecordHeader.BYTES + 100) + (RecordHeader.BYTES + 120));
            segment.fill((byte) 0);

            long pos = writeRecord(segment, 0, 100, 32);
            writeRecord(segment, pos, 120, 48);

            ChunkIndex index = new ChunkIndex();
            index.build(segment, SAMPLE_RATE);

            assertEquals(0, index.findChunkBySample(0L));
            assertEquals(0, index.findChunkBySample(31L));
            assertEquals(1, index.findChunkBySample(32L));
            assertEquals(1, index.findChunkBySample(79L));
            assertEquals(-1, index.findChunkBySample(80L)); // == totalSamples(), out of range
            assertEquals(-1, index.findChunkBySample(-1L));
        }
    }

    @Test
    void findChunkBySample_stubReturnsNegative() {
        ChunkIndex index = new ChunkIndex();
        assertEquals(-1, index.findChunkBySample(0L));
        assertTrue(index.table().length == 0);
    }

    /**
     * Cross-checks the index against the same real SoundTrap fixture,
     * confirming the record header's sample-count field really does sum to
     * OceanInstruments' own reported total ({@code SampleCount="172813552"} in
     * {@code 7867.230815161432.log.xml}) once every record is walked.
     */
    @Test
    void build_matchesRealFixtureChunkAndSampleCounts() throws Exception {
        Path sud = Path.of("src/test/resources/7867.230815161432.sud");

        try (SudFileMapper mapper = new SudFileMapper(sud)) {
            FileMetadata metadata = mapper.parseHeader();

            ChunkIndex index = new ChunkIndex();
            index.build(mapper.mappedFile(), metadata.sampleRate());

            assertEquals(40_673, index.chunkCount());
            assertEquals(172_813_552L, index.totalSamples());
        }
    }
}
