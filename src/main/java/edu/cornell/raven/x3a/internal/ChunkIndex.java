package edu.cornell.raven.x3a.internal;

import edu.cornell.raven.x3a.sud.SudFileMapper;

import java.lang.foreign.MemorySegment;

/// Flattened in-memory index table for fast random-access seeking without `.sudx`
/// sidecar files.
///
/// Layout per audio chunk (4 longs): `[Sample_Offset, File_Byte_Offset, Chunk_Length,
/// Frame_Timestamp]`.
public final class ChunkIndex {

    /// Longs per chunk index entry.
    public static final int STRIDE = 4;
    /// Index-entry offset of a chunk's starting sample.
    public static final int SAMPLE_OFFSET = 0;
    /// Index-entry offset of a chunk's starting byte in the mapped file.
    public static final int FILE_BYTE_OFFSET = 1;
    /// Index-entry offset of a chunk's payload length in bytes.
    public static final int CHUNK_LENGTH = 2;
    /// Index-entry offset of a chunk's timestamp in nanoseconds.
    public static final int FRAME_TIMESTAMP = 3;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private long[] table;
    private int chunkCount;
    private long totalSamples;

    /// Empty index; populate via [#build].
    public ChunkIndex() {
        this.table = new long[0];
        this.chunkCount = 0;
        this.totalSamples = 0L;
    }

    /// Single-pass scan walking every record via the shared [RecordHeader] framing,
    /// skipping metadata/event records (including the trailing end-of-session one) and
    /// indexing everything else as an audio chunk.
    ///
    /// Each chunk's own header field is its decoded sample count (see
    /// [RecordHeader#sampleCountOrRecordType]), so `Sample_Offset` and `Frame_Timestamp`
    /// come out exact rather than approximated from compressed byte length.
    ///
    /// @param mappedFile zero-copy mapped `.SUD` file
    /// @param sampleRate decoded audio sample rate (Hz), used to convert cumulative
    ///                   sample counts into `Frame_Timestamp` nanoseconds
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

    /// Number of indexed audio chunks.
    public int chunkCount() {
        return chunkCount;
    }

    /// Total decoded sample count across every indexed audio chunk.
    public long totalSamples() {
        return totalSamples;
    }

    /// Raw flattened index table; see the class doc for its per-chunk layout.
    public long[] table() {
        return table;
    }

    /// Upper-bound binary search over sample offsets. Returns the index of the chunk
    /// containing `sample`, or `-1` if `sample` is negative or beyond [#totalSamples()].
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

    /// Starting sample of the given chunk.
    public long sampleOffset(int chunkIndex) {
        return table[chunkIndex * STRIDE + SAMPLE_OFFSET];
    }

    /// Starting byte of the given chunk in the mapped file.
    public long fileByteOffset(int chunkIndex) {
        return table[chunkIndex * STRIDE + FILE_BYTE_OFFSET];
    }

    /// Payload length in bytes of the given chunk.
    public long chunkLength(int chunkIndex) {
        return table[chunkIndex * STRIDE + CHUNK_LENGTH];
    }

    /// Timestamp in nanoseconds of the given chunk.
    public long frameTimestamp(int chunkIndex) {
        return table[chunkIndex * STRIDE + FRAME_TIMESTAMP];
    }
}
