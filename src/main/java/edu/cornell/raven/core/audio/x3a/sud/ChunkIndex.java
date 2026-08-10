package edu.cornell.raven.core.audio.x3a.sud;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Phase 2: Flattened in-memory index table for fast seeking without {@code .sudx} sidecars.
 * <p>
 * Layout per audio chunk (4 longs):
 * {@code [Sample_Offset, File_Byte_Offset, Chunk_Length, Frame_Timestamp]}.
 */
public final class ChunkIndex {

    public static final int STRIDE = 4;
    public static final int SAMPLE_OFFSET = 0;
    public static final int FILE_BYTE_OFFSET = 1;
    public static final int CHUNK_LENGTH = 2;
    public static final int FRAME_TIMESTAMP = 3;

    private static final int HEADER_BYTES = 13;
    private static final long DEFAULT_HEADER_SKIP = 128L;

    private long[] table;
    private int chunkCount;

    public ChunkIndex() {
        this.table = new long[0];
        this.chunkCount = 0;
    }

    /**
     * Single-pass scan that skips payloads using chunk size headers and indexes acoustic chunks only.
     */
    public void build(MemorySegment mappedFile) {
        long currentByteOffset = DEFAULT_HEADER_SKIP;
        long fileSize = mappedFile.byteSize();
        long cumulativeSamples = 0L;

        // Conservative initial capacity; grow as needed.
        long[] working = new long[1024 * STRIDE];
        int count = 0;

        while (currentByteOffset + HEADER_BYTES <= fileSize) {
            byte chunkTypeId = mappedFile.get(ValueLayout.JAVA_BYTE, currentByteOffset);
            int payloadLength = mappedFile.get(ValueLayout.JAVA_INT_UNALIGNED, currentByteOffset + 1);
            long timestamp = mappedFile.get(ValueLayout.JAVA_LONG_UNALIGNED, currentByteOffset + 5);

            if (payloadLength < 0 || currentByteOffset + HEADER_BYTES + payloadLength > fileSize) {
                break;
            }

            if (ChunkType.fromId(chunkTypeId) == ChunkType.ACOUSTIC_AUDIO) {
                if ((count + 1) * STRIDE > working.length) {
                    long[] grown = new long[working.length * 2];
                    System.arraycopy(working, 0, grown, 0, working.length);
                    working = grown;
                }
                int idx = count * STRIDE;
                working[idx + SAMPLE_OFFSET] = cumulativeSamples;
                working[idx + FILE_BYTE_OFFSET] = currentByteOffset;
                working[idx + CHUNK_LENGTH] = payloadLength;
                working[idx + FRAME_TIMESTAMP] = timestamp;
                count++;
                // TODO: accumulate true sample counts from chunk headers once format is known.
                cumulativeSamples += payloadLength; // placeholder until samples-per-chunk is decoded
            }

            currentByteOffset += HEADER_BYTES + (long) payloadLength;
        }

        this.chunkCount = count;
        this.table = new long[count * STRIDE];
        System.arraycopy(working, 0, this.table, 0, this.table.length);
    }

    public int chunkCount() {
        return chunkCount;
    }

    public long[] table() {
        return table;
    }

    /**
     * Binary search over sample offsets. Returns the chunk index containing {@code sample},
     * or {@code -1} if out of range.
     */
    public int findChunkBySample(long sample) {
        // TODO: implement binary search on SAMPLE_OFFSET column.
        return -1;
    }

    public long sampleOffset(int chunkIndex) {
        return table[chunkIndex * STRIDE + SAMPLE_OFFSET];
    }

    public long fileByteOffset(int chunkIndex) {
        return table[chunkIndex * STRIDE + FILE_BYTE_OFFSET];
    }

    public long chunkLength(int chunkIndex) {
        return table[chunkIndex * STRIDE + CHUNK_LENGTH];
    }

    public long frameTimestamp(int chunkIndex) {
        return table[chunkIndex * STRIDE + FRAME_TIMESTAMP];
    }
}
