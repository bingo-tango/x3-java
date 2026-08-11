package edu.cornell.raven.core.audio.x3a;

/// X3V2 frame encoder (diff predictor + RICE0/1/3 + BFP), producing payloads
/// [X3AudioDecoder] can unpack bit-for-bit.
///
/// Defaults match the public x3 archive codec (`BLKLEN=20`, rice orders 0/1/3,
/// thresholds 3/8/20). Hot residual loops use pre-sized primitive scratch only, with
/// no per-sample allocation.
public final class X3AudioEncoder {

    /// Default block length matching the public x3 archive codec.
    public static final int DEFAULT_BLOCK_LEN = 20;
    /// Default blocks per frame matching the public x3 archive codec.
    public static final int DEFAULT_BLOCKS_PER_FRAME = 500;
    /// Default rice orders (0/1/3) matching the public x3 archive codec.
    public static final int[] DEFAULT_RICE_ORDERS = {0, 1, 3};
    /// Default per-order residual magnitude thresholds selecting which rice order to use.
    public static final int[] DEFAULT_THRESHOLDS = {3, 8, 20};

    private static final int MAX_CHANNELS = 8;
    private static final int MAX_BLOCK_LEN = 64;

    private final int blockLen;
    private final int blocksPerFrame;
    private final int[] riceOrders;
    private final int[] thresholds;

    /// Per-channel residual scratch for one block.
    private final int[] diffScratch = new int[MAX_BLOCK_LEN];

    /// Public x3 archive codec defaults.
    public X3AudioEncoder() {
        this(DEFAULT_BLOCK_LEN, DEFAULT_BLOCKS_PER_FRAME, DEFAULT_RICE_ORDERS, DEFAULT_THRESHOLDS);
    }

    /// @param riceOrders  exactly 3 orders, written into the archive's `<CODES>` config
    /// @param thresholds  exactly 3 magnitude thresholds selecting between those orders
    public X3AudioEncoder(int blockLen, int blocksPerFrame, int[] riceOrders, int[] thresholds) {
        if (blockLen <= 0 || blockLen > MAX_BLOCK_LEN) {
            throw new IllegalArgumentException("blockLen must be in 1.." + MAX_BLOCK_LEN);
        }
        if (blocksPerFrame <= 0) {
            throw new IllegalArgumentException("blocksPerFrame must be > 0");
        }
        if (riceOrders == null || riceOrders.length != 3) {
            throw new IllegalArgumentException("riceOrders must be length 3");
        }
        if (thresholds == null || thresholds.length != 3) {
            throw new IllegalArgumentException("thresholds must be length 3");
        }
        this.blockLen = blockLen;
        this.blocksPerFrame = blocksPerFrame;
        this.riceOrders = new int[] {riceOrders[0], riceOrders[1], riceOrders[2]};
        this.thresholds = new int[] {thresholds[0], thresholds[1], thresholds[2]};
    }

    /// Configured block length; frame payloads built from this encoder require a decoder
    /// configured with the same value.
    public int blockLen() {
        return blockLen;
    }

    /// Configured blocks per frame — bounds how many samples [#encodeFrame] packs at once.
    public int blocksPerFrame() {
        return blocksPerFrame;
    }

    /// `blockLen * blocksPerFrame` — samples per channel in one frame.
    public int samplesPerFrame() {
        return blockLen * blocksPerFrame;
    }

    /// Configured rice orders, written into the archive's `<CODES>` config so a decoder
    /// reading the archive later knows which orders to use.
    public int[] riceOrders() {
        return new int[] {riceOrders[0], riceOrders[1], riceOrders[2]};
    }

    /// Configured magnitude thresholds selecting between rice orders.
    public int[] thresholds() {
        return new int[] {thresholds[0], thresholds[1], thresholds[2]};
    }

    /// Encodes one frame of interleaved PCM into a fresh, word-aligned bit packer.
    ///
    /// @param pcm       interleaved samples
    /// @param offset    start index in `pcm`
    /// @param frames    number of frames (sample groups) to encode; must be > 0
    /// @param channels  channel count
    /// @return packed payload bytes + CRC state in the writer
    public BitstreamWriter encodeFrame(short[] pcm, int offset, int frames, int channels) {
        BitstreamWriter bp = new BitstreamWriter(Math.max(64, frames * channels));
        encodeFrame(pcm, offset, frames, channels, bp);
        return bp;
    }

    /// Encodes one frame into an existing writer — lets a caller reuse one [BitstreamWriter]
    /// across frames (via [BitstreamWriter#reset()]) instead of allocating per frame.
    public void encodeFrame(short[] pcm, int offset, int frames, int channels, BitstreamWriter bp) {
        if (channels <= 0 || channels > MAX_CHANNELS) {
            throw new IllegalArgumentException("channels must be in 1.." + MAX_CHANNELS);
        }
        if (frames <= 0) {
            throw new IllegalArgumentException("frames must be > 0");
        }
        int need = frames * channels;
        if (offset < 0 || offset + need > pcm.length) {
            throw new IllegalArgumentException("pcm range out of bounds");
        }

        // Filter state: first sample per channel as signed 16-bit (big-endian stream).
        for (int ch = 0; ch < channels; ch++) {
            bp.writeBits(pcm[offset + ch] & 0xffff, 16);
        }

        int framesDone = 1;
        int readBase = offset + channels;
        while (framesDone < frames) {
            int n = frames - framesDone;
            if (n > blockLen) {
                n = blockLen;
            }
            for (int ch = 0; ch < channels; ch++) {
                short last = pcm[readBase - channels + ch];
                int p = readBase + ch;
                for (int i = 0; i < n; i++) {
                    short s = pcm[p];
                    diffScratch[i] = (int) s - (int) last;
                    last = s;
                    p += channels;
                }
                encodeBlock(bp, diffScratch, n, pcm, readBase + ch, channels);
            }
            framesDone += n;
            readBase += n * channels;
        }
        bp.wordAlign();
    }

    /// Selects rice / BFP / pass-through for one channel block of residuals.
    void encodeBlock(BitstreamWriter bp, int[] diffs, int n, short[] pcm, int firstSampleIndex, int stride) {
        int maxAbs = 0;
        for (int i = 0; i < n; i++) {
            int a = diffs[i];
            if (a < 0) {
                a = -a;
            }
            if (a > maxAbs) {
                maxAbs = a;
            }
        }

        if (maxAbs <= thresholds[2]) {
            int ftype = 0;
            for (int t = 0; t < 3; t++) {
                if (maxAbs > thresholds[t]) {
                    ftype++;
                }
            }
            bp.writeBits(ftype + 1, 2);
            packRice(bp, diffs, n, riceOrders[ftype]);
            return;
        }

        int magnitudeBits = maxAbs == 0 ? 0 : (32 - Integer.numberOfLeadingZeros(maxAbs));
        if (magnitudeBits >= 15) {
            // 6-bit BFP header value 15 → decoder nb = 16 pass-through of raw samples.
            bp.writeBits(15, 6);
            int p = firstSampleIndex;
            for (int i = 0; i < n; i++) {
                bp.writeBits(pcm[p] & 0xffff, 16);
                p += stride;
            }
        } else {
            // Header is 6 bits: code 00 + (nb-1) where sample width = magnitudeBits+1.
            bp.writeBits(magnitudeBits, 6);
            int nb = magnitudeBits + 1;
            int mask = (1 << nb) - 1;
            for (int i = 0; i < n; i++) {
                bp.writeBits(diffs[i] & mask, nb);
            }
        }
    }

    /// Packs residuals with the given rice order, matching [X3AudioDecoder]'s unpack.
    static void packRice(BitstreamWriter bp, int[] diffs, int n, int riceOrder) {
        for (int i = 0; i < n; i++) {
            int index = residualToRiceIndex(diffs[i]);
            int zeros = riceOrder == 0 ? index : (index >>> riceOrder);
            int suffix = riceOrder == 0 ? 0 : (index & ((1 << riceOrder) - 1));
            // zeros zero-bits then a terminating 1
            bp.writeBits(1, zeros + 1);
            if (riceOrder > 0) {
                bp.writeBits(suffix, riceOrder);
            }
        }
    }

    /// Inverse of the decoder's `INV_RICE` mapping: 0, -1, 1, -2, 2, ...
    static int residualToRiceIndex(int residual) {
        if (residual == 0) {
            return 0;
        }
        if (residual < 0) {
            return (-residual) * 2 - 1;
        }
        return residual * 2;
    }
}
