package edu.cornell.raven.core.audio.x3a.internal;

import edu.cornell.raven.core.audio.x3a.X3AudioDecoder;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/// Parallel chunk decompression over virtual threads, for random-access windowed decode.
///
/// Work units are stateless slices; callers (typically the `...sud` facade) supply the
/// flattened index table from the container layer, so this codec package stays free of
/// any SUD-specific type.
///
/// Index layout per chunk (4 longs): `[Sample_Offset, File_Byte_Offset, Chunk_Length,
/// Frame_Timestamp]`. `File_Byte_Offset` points at the start of the container record;
/// `payloadHeaderBytes` skips any fixed header before the X3 bitstream.
///
/// Active chunk work is bounded by a per-pipeline semaphore (`maxConcurrency`) and,
/// when configured, the process-wide [DecodeScheduler#sharedLimiter()] — so many
/// concurrent decoders in one process don't oversubscribe CPU. Fan-out uses a
/// virtual-thread-per-task executor (the stable API, avoiding preview `StructuredTaskScope`).
public final class ChunkPipeline {

    /// Longs per chunk index entry.
    public static final int INDEX_STRIDE = 4;
    /// Index-entry offset of a chunk's starting sample.
    public static final int SAMPLE_OFFSET = 0;
    /// Index-entry offset of a chunk's starting byte in the mapped file.
    public static final int FILE_BYTE_OFFSET = 1;
    /// Index-entry offset of a chunk's payload length in bytes.
    public static final int CHUNK_LENGTH = 2;

    /// Below this many chunks in a window, parallel fan-out is skipped — task setup would
    /// cost more than the sequential decode it replaces.
    private static final int PARALLEL_CHUNK_FLOOR = 2;

    private final MemorySegment mappedFile;
    private final long[] indexTable;
    private final int chunkCount;
    private final long totalSamples;
    private final X3AudioDecoder audioDecoder;
    private final int channels;
    private final int maxConcurrency;
    private final Semaphore localLimiter;
    private final Semaphore sharedLimiter; // null if disabled
    private final int payloadHeaderBytes;
    private final boolean sudPayload;

    /// Reusable sequential-path scratch (not used by parallel tasks).
    private short[] seqScratch = new short[8192];

    /// Legacy constructor without payload framing options; assumes bare (non-SUD) payloads.
    public ChunkPipeline(MemorySegment mappedFile,
                         long[] indexTable,
                         int chunkCount,
                         X3AudioDecoder audioDecoder,
                         int channels,
                         int maxConcurrency) {
        this(mappedFile, indexTable, chunkCount, inferTotalSamples(indexTable, chunkCount),
                audioDecoder, channels, DecodeOptions.defaults()
                        .withMaxConcurrency(maxConcurrency));
    }

    /// Builds a pipeline over an already-computed chunk index.
    ///
    /// @param totalSamples explicit sample count, since the last chunk's length can't be
    ///                      inferred from the index table alone
    public ChunkPipeline(MemorySegment mappedFile,
                         long[] indexTable,
                         int chunkCount,
                         long totalSamples,
                         X3AudioDecoder audioDecoder,
                         int channels,
                         DecodeOptions options) {
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        this.mappedFile = mappedFile;
        this.indexTable = indexTable != null ? indexTable : new long[0];
        this.chunkCount = Math.max(0, chunkCount);
        this.totalSamples = Math.max(0L, totalSamples);
        this.audioDecoder = audioDecoder != null ? audioDecoder : new X3AudioDecoder();
        this.channels = Math.max(1, channels);
        this.maxConcurrency = Math.max(1, options.maxConcurrency());
        this.localLimiter = new Semaphore(this.maxConcurrency, false);
        this.sharedLimiter = options.useSharedLimiter() ? options.sharedLimiter() : null;
        this.payloadHeaderBytes = options.payloadHeaderBytes();
        this.sudPayload = options.sudPayload();
    }

    /// Decodes a closed sample window `[startSample, startSample + length)` into interleaved
    /// `dest` (capacity `length * channels` from index 0).
    ///
    /// @return number of PCM frames written
    public int decodeWindowInt(long startSample, int length, short[] dest) {
        if (length <= 0 || chunkCount == 0 || dest == null) {
            return 0;
        }
        if (startSample < 0 || startSample >= totalSamples) {
            return 0;
        }
        long end = startSample + length;
        if (end > totalSamples) {
            end = totalSamples;
            length = (int) (end - startSample);
        }

        int first = findChunkBySample(startSample);
        if (first < 0) {
            return 0;
        }
        int last = findChunkBySample(end - 1);
        if (last < 0) {
            last = chunkCount - 1;
        }
        int span = last - first + 1;
        if (span < PARALLEL_CHUNK_FLOOR || maxConcurrency == 1) {
            return decodeSequential(startSample, end, length, first, dest);
        }
        return decodeParallel(startSample, end, length, first, last, dest);
    }

    /// Same as [#decodeWindowInt] with on-the-fly float normalization into `dest`.
    /// `scratch` must hold at least `length * channels` samples.
    ///
    /// @return number of PCM frames written
    public int decodeWindowFloat(long startSample, int length, float[] dest, short[] scratch) {
        if (length <= 0) {
            return 0;
        }
        int frames = decodeWindowInt(startSample, length, scratch);
        int samples = frames * channels;
        final float scale = 1.0f / 32768.0f;
        for (int i = 0; i < samples; i++) {
            dest[i] = scratch[i] * scale;
        }
        return frames;
    }

    /// Configured cap on concurrently in-flight chunk decode tasks for this pipeline.
    public int maxConcurrency() {
        return maxConcurrency;
    }

    /// Channel count decoded chunks are interleaved with.
    public int channels() {
        return channels;
    }

    /// Total decodable samples, the upper bound for [#decodeWindowInt] windows.
    public long totalSamples() {
        return totalSamples;
    }

    /// Whether this pipeline also acquires the process-wide [DecodeScheduler#sharedLimiter()].
    public boolean usesSharedLimiter() {
        return sharedLimiter != null;
    }

    private int decodeSequential(long startSample, long end, int length, int firstChunk, short[] dest) {
        int framesWritten = 0;
        int chunk = firstChunk;
        while (framesWritten < length && chunk < chunkCount) {
            long chunkStart = sampleOffset(chunk);
            int chunkSamples = chunkSampleCount(chunk);
            decodeChunkIntoScratch(chunk, chunkSamples);

            long copyFrom = Math.max(startSample, chunkStart);
            long copyTo = Math.min(end, chunkStart + chunkSamples);
            int localFrom = (int) (copyFrom - chunkStart);
            int n = (int) (copyTo - copyFrom);
            System.arraycopy(seqScratch, localFrom * channels,
                    dest, framesWritten * channels, n * channels);

            framesWritten += n;
            chunk++;
        }
        return framesWritten;
    }

    private int decodeParallel(long startSample, long end, int length,
                               int firstChunk, int lastChunk, short[] dest) {
        List<Callable<Void>> tasks = new ArrayList<>(lastChunk - firstChunk + 1);
        for (int chunk = firstChunk; chunk <= lastChunk; chunk++) {
            final int chunkIndex = chunk;
            final long chunkStart = sampleOffset(chunkIndex);
            final int chunkSamples = chunkSampleCount(chunkIndex);
            final long copyFrom = Math.max(startSample, chunkStart);
            final long copyTo = Math.min(end, chunkStart + chunkSamples);
            if (copyTo <= copyFrom) {
                continue;
            }
            final int localFrom = (int) (copyFrom - chunkStart);
            final int n = (int) (copyTo - copyFrom);
            final int destBase = (int) (copyFrom - startSample) * channels;

            tasks.add(() -> {
                acquirePermits();
                try {
                    // Task-local decoder + scratch: X3AudioDecoder keeps block scratch on the instance.
                    X3AudioDecoder local = audioDecoder.newInstance();
                    short[] scratch = new short[chunkSamples * channels];
                    long fileOff = fileByteOffset(chunkIndex) + payloadHeaderBytes;
                    int payloadLen = (int) chunkLength(chunkIndex);
                    MemorySegment payload = mappedFile.asSlice(fileOff, payloadLen);
                    local.decodeChunkInt(payload, chunkSamples, channels, scratch, 0, sudPayload);
                    System.arraycopy(scratch, localFrom * channels, dest, destBase, n * channels);
                    return null;
                } finally {
                    releasePermits();
                }
            });
        }
        if (tasks.isEmpty()) {
            return 0;
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during parallel decode", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("parallel decode failed", cause);
        }
        return length;
    }

    private void decodeChunkIntoScratch(int chunk, int chunkSamples) {
        ensureSeqScratch(chunkSamples * channels);
        long fileOff = fileByteOffset(chunk) + payloadHeaderBytes;
        int payloadLen = (int) chunkLength(chunk);
        MemorySegment payload = mappedFile.asSlice(fileOff, payloadLen);
        audioDecoder.decodeChunkInt(payload, chunkSamples, channels, seqScratch, 0, sudPayload);
    }

    private void acquirePermits() throws InterruptedException {
        localLimiter.acquire();
        if (sharedLimiter != null) {
            try {
                sharedLimiter.acquire();
            } catch (InterruptedException | RuntimeException e) {
                localLimiter.release();
                throw e;
            }
        }
    }

    private void releasePermits() {
        if (sharedLimiter != null) {
            sharedLimiter.release();
        }
        localLimiter.release();
    }

    private int findChunkBySample(long sample) {
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

    private long sampleOffset(int chunkIndex) {
        return indexTable[chunkIndex * INDEX_STRIDE + SAMPLE_OFFSET];
    }

    private long fileByteOffset(int chunkIndex) {
        return indexTable[chunkIndex * INDEX_STRIDE + FILE_BYTE_OFFSET];
    }

    private long chunkLength(int chunkIndex) {
        return indexTable[chunkIndex * INDEX_STRIDE + CHUNK_LENGTH];
    }

    private int chunkSampleCount(int chunkIndex) {
        long start = sampleOffset(chunkIndex);
        long end = (chunkIndex + 1 < chunkCount)
                ? sampleOffset(chunkIndex + 1)
                : totalSamples;
        return (int) (end - start);
    }

    private void ensureSeqScratch(int samples) {
        if (seqScratch.length < samples) {
            seqScratch = new short[samples];
        }
    }

    private static long inferTotalSamples(long[] indexTable, int chunkCount) {
        if (indexTable == null || chunkCount <= 0) {
            return 0L;
        }
        // Without an explicit total, last sample offset is the best lower bound (incomplete).
        return indexTable[(chunkCount - 1) * INDEX_STRIDE + SAMPLE_OFFSET];
    }
}
