package edu.cornell.raven.core.audio.x3a;

import java.lang.foreign.MemorySegment;

/**
 * Phase 3: JIT-friendly X3 predictive audio unpacking into flattened 1D PCM buffers.
 * Multi-channel output is interleaved in a single primitive array (no {@code short[][]}).
 * <p>
 * Implements X3V2 block coding (RICE0 / RICE1 / RICE3 / BFP) with the diff predictor,
 * matching the Johnson et al. codec and the PAMGuard / x3-rust reference decoders.
 * SoundTrap {@code .SUD} payloads must be read with pair-wise endian swap
 * ({@code sudPayload=true}); bare {@code .x3a} frame bodies use {@code sudPayload=false}.
 */
public final class X3AudioDecoder {

    private static final float SCALE_TO_UNIT = 1.0f / 32768.0f;

    /** Inverse Rice residual table: 0, -1, 1, -2, 2, ... */
    private static final short[] INV_RICE = makeInverseRice(64);

    private static final int MAX_CHANNELS = 8;
    private static final int MAX_BLOCK_LEN = 64;

    private final int blockLen;
    private final int[] riceOrders;

    /** Scratch for one channel's block residuals (reused; no per-block alloc). */
    private final short[] blockScratch = new short[MAX_BLOCK_LEN];

    public X3AudioDecoder() {
        this(16, new int[] {0, 1, 3});
    }

    public X3AudioDecoder(int blockLen, int[] riceOrders) {
        if (blockLen <= 0 || blockLen > MAX_BLOCK_LEN) {
            throw new IllegalArgumentException("blockLen must be in 1.." + MAX_BLOCK_LEN);
        }
        if (riceOrders == null || riceOrders.length != 3) {
            throw new IllegalArgumentException("riceOrders must be length 3");
        }
        this.blockLen = blockLen;
        this.riceOrders = new int[] {riceOrders[0], riceOrders[1], riceOrders[2]};
    }

    public int blockLen() {
        return blockLen;
    }

    /**
     * Decodes one acoustic chunk / frame payload into a caller-owned interleaved {@code short[]} buffer.
     *
     * @param payload     compressed X3 payload (filter state + blocks)
     * @param sampleCount number of PCM frames in this chunk (from SUD header or X3 frame header)
     * @param channels    channel count
     * @param dest        interleaved destination
     * @param destOffset  start index in {@code dest}
     * @param sudPayload  {@code true} to apply SUD pair-wise byte swap while reading
     * @return number of PCM frames written
     */
    public int decodeChunkInt(MemorySegment payload, int sampleCount, int channels,
                              short[] dest, int destOffset, boolean sudPayload) {
        if (channels <= 0 || channels > MAX_CHANNELS) {
            throw new IllegalArgumentException("channels must be in 1.." + MAX_CHANNELS);
        }
        if (sampleCount <= 0) {
            return 0;
        }
        int need = sampleCount * channels;
        if (destOffset < 0 || destOffset + need > dest.length) {
            throw new IllegalArgumentException("destination too small for " + need + " samples");
        }

        BitstreamReader br = new BitstreamReader(payload, sudPayload);

        // Filter state: first sample per channel (big-endian 16-bit after optional pair-swap).
        for (int ch = 0; ch < channels; ch++) {
            dest[destOffset + ch] = (short) br.readBits(16);
        }

        int framesDone = 1;
        int writeBase = destOffset + channels;

        while (framesDone < sampleCount) {
            int n = sampleCount - framesDone;
            if (n > blockLen) {
                n = blockLen;
            }
            // SoundTrap short-block headers may shrink n for every channel in this round.
            int actualN = n;
            for (int ch = 0; ch < channels; ch++) {
                short last = dest[writeBase - channels + ch];
                actualN = decodeBlock(br, blockScratch, actualN, last);
                int p = writeBase + ch;
                for (int i = 0; i < actualN; i++) {
                    dest[p] = blockScratch[i];
                    p += channels;
                }
            }
            framesDone += actualN;
            writeBase += actualN * channels;
        }
        return sampleCount;
    }

    /**
     * Convenience: SUD payload (pair-swap on).
     */
    public int decodeChunkInt(MemorySegment payload, int sampleCount, int channels,
                              short[] dest, int destOffset) {
        return decodeChunkInt(payload, sampleCount, channels, dest, destOffset, true);
    }

    /**
     * Decodes and normalizes samples into a caller-owned {@code float[]} in {@code [-1, 1]}.
     * Uses a pure countable multiply loop for HotSpot auto-vectorization.
     */
    public int decodeChunkFloat(MemorySegment payload, int sampleCount, int channels,
                                float[] dest, int destOffset, short[] scratch, boolean sudPayload) {
        int frames = decodeChunkInt(payload, sampleCount, channels, scratch, 0, sudPayload);
        int samples = frames * channels;
        for (int i = 0; i < samples; i++) {
            dest[destOffset + i] = scratch[i] * SCALE_TO_UNIT;
        }
        return frames;
    }

    public int decodeChunkFloat(MemorySegment payload, int sampleCount, int channels,
                                float[] dest, int destOffset, short[] scratch) {
        return decodeChunkFloat(payload, sampleCount, channels, dest, destOffset, scratch, true);
    }

    /**
     * Decodes one coded block into {@code out[0..n)}, returning the actual sample count written
     * (may be smaller than {@code n} for SoundTrap short blocks).
     */
    private int decodeBlock(BitstreamReader br, short[] out, int n, short last) {
        int code = br.readBits(2);
        int nb = 0;

        if (code == 0) {
            // BFP / pass-through. SoundTrap may emit a shortened block header when nb==0.
            nb = br.readBits(4);
            if (nb > 0) {
                nb++;
            } else {
                int nn = br.readBits(6) + 1;
                if (nn > blockLen) {
                    throw new IllegalStateException("bad short-block length: " + nn);
                }
                n = nn;
                code = br.readBits(2);
                if (code == 0) {
                    nb = br.readBits(4) + 1;
                }
            }
        }

        if (code > 0) {
            unpackRice(br, out, n, riceOrders[code - 1]);
            integrate(out, n, last);
        } else {
            unpackBfp(br, out, n, nb);
            if (nb != 16) {
                integrate(out, n, last);
            }
        }
        return n;
    }

    private static void unpackRice(BitstreamReader br, short[] out, int n, int riceOrder) {
        for (int k = 0; k < n; k++) {
            int zeros = br.countZeroBits();
            br.readBits(1); // terminating 1
            int suffix = riceOrder == 0 ? 0 : br.readBits(riceOrder);
            int index = (zeros << riceOrder) + suffix;
            if (index < 0 || index >= INV_RICE.length) {
                throw new IllegalStateException("rice index out of range: " + index);
            }
            out[k] = INV_RICE[index];
        }
    }

    private static void unpackBfp(BitstreamReader br, short[] out, int n, int nb) {
        if (nb <= 0 || nb > 16) {
            throw new IllegalStateException("invalid BFP width: " + nb);
        }
        for (int i = 0; i < n; i++) {
            int raw = br.readBits(nb);
            out[i] = fixSign(raw, nb);
        }
    }

    /** Two's-complement adjust for a {@code nbits}-wide unsigned field. */
    static short fixSign(int d, int nbits) {
        int half = 1 << (nbits - 1);
        int offs = half << 1;
        return (short) (d >= half ? d - offs : d);
    }

    /** Diff-filter inverse: running sum starting from {@code last}. */
    static void integrate(short[] op, int count, short last) {
        int acc = last;
        for (int k = 0; k < count; k++) {
            acc += op[k];
            op[k] = (short) acc;
        }
    }

    private static short[] makeInverseRice(int whereTo) {
        short[] irt = new short[whereTo * 2 + 1];
        int ind = 0;
        for (int i = 1; i <= whereTo; i++) {
            irt[++ind] = (short) -i;
            irt[++ind] = (short) i;
        }
        return irt;
    }
}
