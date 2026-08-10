package edu.cornell.raven.core.audio.x3a;

import java.lang.foreign.MemorySegment;

/**
 * Phase 3: JIT-friendly X3 predictive audio unpacking into flattened 1D PCM buffers.
 * Multi-channel output is interleaved in a single primitive array (no {@code short[][]}).
 */
public final class X3AudioDecoder {

    private static final float SCALE_TO_UNIT = 1.0f / 32768.0f;

    /**
     * Decodes one acoustic chunk payload into a caller-owned interleaved {@code short[]} buffer.
     *
     * @return number of PCM frames written (per-channel sample groups)
     */
    public int decodeChunkInt(MemorySegment payload, int channels, short[] dest, int destOffset) {
        // TODO: Run X3 residual / predictor loops via BitstreamReader into dest.
        // CRITICAL: no allocations, no branching in innermost loops, flat 1D dest only.
        return 0;
    }

    /**
     * Decodes and normalizes samples into a caller-owned {@code float[]} in {@code [-1, 1]}.
     * Uses a pure countable multiply loop for HotSpot auto-vectorization.
     *
     * @return number of PCM frames written
     */
    public int decodeChunkFloat(MemorySegment payload, int channels, float[] dest, int destOffset,
                                short[] scratch) {
        int frames = decodeChunkInt(payload, channels, scratch, 0);
        int samples = frames * channels;
        for (int i = 0; i < samples; i++) {
            dest[destOffset + i] = scratch[i] * SCALE_TO_UNIT;
        }
        return frames;
    }
}
