package edu.cornell.raven.core.audio.x3a.sud;

import java.lang.foreign.MemorySegment;

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

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private long[] table;
    private int chunkCount;
    private long totalSamples;

    public ChunkIndex() {
        this.table = new long[0];
        this.chunkCount = 0;
        this.totalSamples = 0L;
    }

    /**
     * Single-pass scan that walks every record via the shared {@link RecordHeader}
     * framing, skipping metadata/event records (including the trailing
     * end-of-session one) and indexing everything else as an audio chunk. Each
     * chunk's own header field is its decoded sample count (see
     * {@link RecordHeader#sampleCountOrRecordType}), so {@code Sample_Offset} and
     * {@code Frame_Timestamp} are exact, not approximated from compressed byte
     * length.
     *
     * @param mappedFile zero-copy mapped {@code .SUD} file
     * @param sampleRate decoded audio sample rate (Hz), used to convert cumulative
     *                   sample counts into {@code Frame_Timestamp} nanoseconds
     */
    public void build(MemorySegment mappedFile, int sampleRate) {
        long fileSize = mappedFile.byteSize();
        long searchLimit = Math.min(fileSize, SudFileMapper.SYNC_SEARCH_WINDOW);
        long pos = RecordHeader.findFirstSync(mappedFile, searchLimit);

        long cumulativeSamples = 0L;
        long[] working = new long[1024 * STRIDE];
        int count = 0;

        while (pos >= 0 && pos + RecordHeader.BYTES <= fileSize) {
            if (!RecordHeader.hasSyncAt(mappedFile, pos)) {
                break;
            }

            int payloadLength = RecordHeader.payloadLength(mappedFile, pos);
            int sampleCountOrRecordType = RecordHeader.sampleCountOrRecordType(mappedFile, pos);
            long payloadStart = pos + RecordHeader.BYTES;

            if (payloadStart + payloadLength > fileSize) {
                break;
            }

            if (!RecordHeader.isMetadata(sampleCountOrRecordType)) {
                if ((count + 1) * STRIDE > working.length) {
                    long[] grown = new long[working.length * 2];
                    System.arraycopy(working, 0, grown, 0, working.length);
                    working = grown;
                }
                int idx = count * STRIDE;
                working[idx + SAMPLE_OFFSET] = cumulativeSamples;
                working[idx + FILE_BYTE_OFFSET] = pos;
                working[idx + CHUNK_LENGTH] = payloadLength;
                working[idx + FRAME_TIMESTAMP] = cumulativeSamples * NANOS_PER_SECOND / sampleRate;
                count++;
                cumulativeSamples += sampleCountOrRecordType;
            }

            pos = payloadStart + payloadLength;
        }

        this.chunkCount = count;
        this.totalSamples = cumulativeSamples;
        this.table = new long[count * STRIDE];
        System.arraycopy(working, 0, this.table, 0, this.table.length);
    }

    public int chunkCount() {
        return chunkCount;
    }

    /**
     * Total decoded sample count across every indexed audio chunk.
     */
    public long totalSamples() {
        return totalSamples;
    }

    public long[] table() {
        return table;
    }

    /**
     * Upper-bound binary search over sample offsets. Returns the index of the
     * chunk containing {@code sample}, or {@code -1} if {@code sample} is negative
     * or beyond {@link #totalSamples()}.
     */
    public int findChunkBySample(long sample) {
        if (chunkCount == 0 || sample < 0 || sample >= totalSamples) {
            return -1;
        }
        int lo = 0;
        int hi = chunkCount - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (sampleOffset(mid) <= sample) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
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
