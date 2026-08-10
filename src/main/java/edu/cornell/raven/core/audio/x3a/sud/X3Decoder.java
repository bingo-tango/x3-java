package edu.cornell.raven.core.audio.x3a.sud;

import edu.cornell.raven.core.audio.x3a.X3AudioDecoder;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class X3Decoder implements AutoCloseable {

    private static final float SCALE_TO_UNIT = 1.0f / 32768.0f;
    private static final Pattern BLKLEN = Pattern.compile("<BLKLEN>\\s*(\\d+)\\s*</BLKLEN>");

    // Phase 1: Zero-copy mapping + metadata ingestion (converged here per the facade's
    // FAC --> MAP dependency in the architecture diagram, rather than duplicating it).
    private final SudFileMapper mapper;
    private final MemorySegment mappedFile;

    // Config metadata extracted from Phase 1 (sample rate, channels, bit depth, device tags)
    private final FileMetadata metadata;

    // Phase 2: Flattened in-memory index table (see ChunkIndex for column layout)
    private final ChunkIndex chunkIndex;

    // Phase 3: X3 bitstream unpacker (stateless per call aside from reusable scratch)
    private final X3AudioDecoder audioDecoder;
    private final int channels;

    /** Reusable per-chunk PCM scratch (grows to largest chunk seen). */
    private short[] chunkScratch = new short[8192];

    /** Reusable int→float scratch for {@link #decodeSamplesFloat}. */
    private short[] floatScratch = new short[0];

    public X3Decoder(Path sudFilePath) throws Exception {
        this.mapper = new SudFileMapper(sudFilePath);
        this.mappedFile = mapper.mappedFile();
        this.metadata = mapper.parseHeader();
        this.chunkIndex = new ChunkIndex();
        this.chunkIndex.build(mappedFile, metadata.sampleRate());
        this.channels = Math.max(1, metadata.channels());
        int blockLen = parseBlockLen(metadata.xmlConfig(), 16);
        this.audioDecoder = new X3AudioDecoder(blockLen, new int[] {0, 1, 3});
    }

    /**
     * Phase 1 file configuration, needed by any consumer building output headers
     * (sample rate, channel count, bit depth, device tags).
     */
    public FileMetadata metadata() {
        return metadata;
    }

    /**
     * Phase 2 in-memory index table, allowing random seeking by sample without
     * {@code .sudx} sidecar files.
     */
    public ChunkIndex chunkIndex() {
        return chunkIndex;
    }

    /**
     * High-Performance Integer Read: Direct 1D array delivery for libsndfile/FLAC pipeline.
     * CRITICAL RULE: Destination array must be pre-allocated by the caller to preserve zero allocation.
     *
     * @param startSample first frame index (per channel)
     * @param length      number of frames to decode
     * @param destIntBuffer interleaved destination ({@code length * channels} capacity from index 0)
     * @return number of frames written
     */
    public int decodeSamplesInt(long startSample, int length, short[] destIntBuffer) {
        if (length <= 0) {
            return 0;
        }
        long total = chunkIndex.totalSamples();
        if (startSample < 0 || startSample >= total) {
            return 0;
        }
        long end = startSample + length;
        if (end > total) {
            end = total;
            length = (int) (end - startSample);
        }

        int framesWritten = 0;
        int chunk = chunkIndex.findChunkBySample(startSample);
        if (chunk < 0) {
            return 0;
        }

        while (framesWritten < length && chunk < chunkIndex.chunkCount()) {
            long chunkStart = chunkIndex.sampleOffset(chunk);
            int chunkSamples = chunkSampleCount(chunk);
            long payloadOff = chunkIndex.fileByteOffset(chunk) + RecordHeader.BYTES;
            int payloadLen = (int) chunkIndex.chunkLength(chunk);

            ensureChunkScratch(chunkSamples * channels);
            MemorySegment payload = mappedFile.asSlice(payloadOff, payloadLen);
            audioDecoder.decodeChunkInt(payload, chunkSamples, channels, chunkScratch, 0, true);

            long copyFrom = Math.max(startSample, chunkStart);
            long copyTo = Math.min(end, chunkStart + chunkSamples);
            int localFrom = (int) (copyFrom - chunkStart);
            int n = (int) (copyTo - copyFrom);
            int destBase = framesWritten * channels;
            int srcBase = localFrom * channels;
            int nSamples = n * channels;
            System.arraycopy(chunkScratch, srcBase, destIntBuffer, destBase, nSamples);

            framesWritten += n;
            chunk++;
        }
        return framesWritten;
    }

    /**
     * High-Performance Float Read: Direct on-the-fly normalization scaling for float apps.
     * CRITICAL RULE: Absolutely zero allocation on the steady-state path (scratch grows once).
     */
    public int decodeSamplesFloat(long startSample, int length, float[] destFloatBuffer) {
        if (length <= 0) {
            return 0;
        }
        int need = length * channels;
        if (floatScratch.length < need) {
            floatScratch = new short[need];
        }
        int readFrames = decodeSamplesInt(startSample, length, floatScratch);
        int samples = readFrames * channels;
        for (int i = 0; i < samples; i++) {
            destFloatBuffer[i] = floatScratch[i] * SCALE_TO_UNIT;
        }
        return readFrames;
    }

    private int chunkSampleCount(int chunkIndexPos) {
        long start = chunkIndex.sampleOffset(chunkIndexPos);
        long end = (chunkIndexPos + 1 < chunkIndex.chunkCount())
                ? chunkIndex.sampleOffset(chunkIndexPos + 1)
                : chunkIndex.totalSamples();
        return (int) (end - start);
    }

    private void ensureChunkScratch(int samples) {
        if (chunkScratch.length < samples) {
            chunkScratch = new short[samples];
        }
    }

    static int parseBlockLen(String xml, int defaultLen) {
        if (xml == null || xml.isEmpty()) {
            return defaultLen;
        }
        Matcher m = BLKLEN.matcher(xml);
        if (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v > 0 && v <= 64) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return defaultLen;
    }

    @Override
    public void close() {
        mapper.close();
    }

    public static void main(String[] args) {
        System.out.println("X3 Audio Decoder Engine (JDK 25 FFM API)");
    }
}
