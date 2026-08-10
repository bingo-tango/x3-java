package edu.cornell.raven.core.audio.x3a;

import java.util.concurrent.Semaphore;

/**
 * Decode concurrency and payload framing options for {@link ChunkPipeline} / SUD facade.
 * <p>
 * Defaults leave headroom for multi-file hosts: per-decoder
 * {@link DecodeScheduler#defaultPerDecoderConcurrency()} and a process-wide shared limiter
 * on by default ({@link DecodeScheduler#sharedLimiter()}).
 */
public final class DecodeOptions {

    private static final String PROP_MAX = "x3a.decode.maxConcurrency";
    private static final String PROP_SHARED_ON = "x3a.decode.sharedLimiter";

    private final int maxConcurrency;
    private final boolean useSharedLimiter;
    private final Semaphore sharedLimiter;
    private final int payloadHeaderBytes;
    private final boolean sudPayload;

    private DecodeOptions(int maxConcurrency,
                          boolean useSharedLimiter,
                          Semaphore sharedLimiter,
                          int payloadHeaderBytes,
                          boolean sudPayload) {
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.useSharedLimiter = useSharedLimiter;
        this.sharedLimiter = sharedLimiter;
        this.payloadHeaderBytes = Math.max(0, payloadHeaderBytes);
        this.sudPayload = sudPayload;
    }

    /**
     * Library defaults: per-decoder {@code min(4, cores)}, shared limiter on with
     * {@code min(8, cores/2)}, no container header bytes, non-SUD bit packing.
     */
    public static DecodeOptions defaults() {
        int max = DecodeScheduler.positiveIntProperty(PROP_MAX, -1);
        if (max <= 0) {
            max = DecodeScheduler.defaultPerDecoderConcurrency();
        }
        boolean sharedOn = sharedLimiterPropertyDefaultTrue();
        return new DecodeOptions(max, sharedOn, DecodeScheduler.sharedLimiter(), 0, false);
    }

    /**
     * Defaults for SoundTrap {@code .SUD}: pair-swap payloads and skip the 20-byte record header
     * before the X3 bitstream (see container {@code RecordHeader.BYTES}).
     */
    public static DecodeOptions sudDefaults(int recordHeaderBytes) {
        return defaults().withPayloadHeaderBytes(recordHeaderBytes).withSudPayload(true);
    }

    public DecodeOptions withMaxConcurrency(int maxConcurrency) {
        return new DecodeOptions(maxConcurrency, useSharedLimiter, sharedLimiter, payloadHeaderBytes, sudPayload);
    }

    public DecodeOptions withSharedLimiter(boolean enabled) {
        return new DecodeOptions(maxConcurrency, enabled, sharedLimiter, payloadHeaderBytes, sudPayload);
    }

    /**
     * Use a custom shared semaphore (still only acquired when {@link #useSharedLimiter()} is true).
     */
    public DecodeOptions withSharedLimiter(Semaphore semaphore) {
        if (semaphore == null) {
            throw new IllegalArgumentException("sharedLimiter must not be null");
        }
        return new DecodeOptions(maxConcurrency, true, semaphore, payloadHeaderBytes, sudPayload);
    }

    public DecodeOptions withPayloadHeaderBytes(int payloadHeaderBytes) {
        return new DecodeOptions(maxConcurrency, useSharedLimiter, sharedLimiter, payloadHeaderBytes, sudPayload);
    }

    public DecodeOptions withSudPayload(boolean sudPayload) {
        return new DecodeOptions(maxConcurrency, useSharedLimiter, sharedLimiter, payloadHeaderBytes, sudPayload);
    }

    public int maxConcurrency() {
        return maxConcurrency;
    }

    public boolean useSharedLimiter() {
        return useSharedLimiter;
    }

    /**
     * Semaphore acquired around each chunk task when shared limiting is enabled; never null.
     */
    public Semaphore sharedLimiter() {
        return sharedLimiter != null ? sharedLimiter : DecodeScheduler.sharedLimiter();
    }

    public int payloadHeaderBytes() {
        return payloadHeaderBytes;
    }

    public boolean sudPayload() {
        return sudPayload;
    }

    private static boolean sharedLimiterPropertyDefaultTrue() {
        String raw = System.getProperty(PROP_SHARED_ON);
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return !raw.equalsIgnoreCase("false") && !raw.equals("0");
    }
}
