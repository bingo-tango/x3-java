package edu.cornell.raven.x3a.internal;

import java.util.concurrent.Semaphore;

/// Decode concurrency and payload framing options for [ChunkPipeline] / the SUD facade.
///
/// Immutable and built via `with*` methods so options can be shared/reused across
/// decoders without aliasing surprises. Defaults leave headroom for multi-file hosts:
/// per-decoder [DecodeScheduler#defaultPerDecoderConcurrency()] plus a process-wide
/// shared limiter on by default ([DecodeScheduler#sharedLimiter()]).
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

    /// Library defaults: per-decoder `min(4, cores)`, shared limiter on with
    /// `min(8, cores/2)`, no container header bytes, non-SUD bit packing.
    public static DecodeOptions defaults() {
        int max = DecodeScheduler.positiveIntProperty(PROP_MAX, -1);
        if (max <= 0) {
            max = DecodeScheduler.defaultPerDecoderConcurrency();
        }
        boolean sharedOn = sharedLimiterPropertyDefaultTrue();
        return new DecodeOptions(max, sharedOn, DecodeScheduler.sharedLimiter(), 0, false);
    }

    /// Defaults for SoundTrap `.SUD`: pair-swap payloads and skip the container's fixed
    /// record header before the X3 bitstream.
    public static DecodeOptions sudDefaults(int recordHeaderBytes) {
        return defaults().withPayloadHeaderBytes(recordHeaderBytes).withSudPayload(true);
    }

    /// Returns a copy with a different concurrency cap.
    public DecodeOptions withMaxConcurrency(int maxConcurrency) {
        return new DecodeOptions(maxConcurrency, useSharedLimiter, sharedLimiter, payloadHeaderBytes, sudPayload);
    }

    /// Returns a copy that does or doesn't also acquire the process-wide shared limiter.
    public DecodeOptions withSharedLimiter(boolean enabled) {
        return new DecodeOptions(maxConcurrency, enabled, sharedLimiter, payloadHeaderBytes, sudPayload);
    }

    /// Returns a copy using a custom shared semaphore instead of [DecodeScheduler#sharedLimiter()]
    /// — still only acquired when [#useSharedLimiter()] is true.
    public DecodeOptions withSharedLimiter(Semaphore semaphore) {
        if (semaphore == null) {
            throw new IllegalArgumentException("sharedLimiter must not be null");
        }
        return new DecodeOptions(maxConcurrency, true, semaphore, payloadHeaderBytes, sudPayload);
    }

    /// Returns a copy that skips `payloadHeaderBytes` before each chunk's X3 bitstream —
    /// for container formats that prefix a fixed record header.
    public DecodeOptions withPayloadHeaderBytes(int payloadHeaderBytes) {
        return new DecodeOptions(maxConcurrency, useSharedLimiter, sharedLimiter, payloadHeaderBytes, sudPayload);
    }

    /// Returns a copy that does or doesn't apply SUD pair-wise byte swap while reading.
    public DecodeOptions withSudPayload(boolean sudPayload) {
        return new DecodeOptions(maxConcurrency, useSharedLimiter, sharedLimiter, payloadHeaderBytes, sudPayload);
    }

    /// Configured concurrency cap.
    public int maxConcurrency() {
        return maxConcurrency;
    }

    /// Whether the process-wide shared limiter is also acquired.
    public boolean useSharedLimiter() {
        return useSharedLimiter;
    }

    /// Semaphore acquired around each chunk task when shared limiting is enabled; never null.
    public Semaphore sharedLimiter() {
        return sharedLimiter != null ? sharedLimiter : DecodeScheduler.sharedLimiter();
    }

    /// Configured bytes to skip before each chunk's X3 bitstream.
    public int payloadHeaderBytes() {
        return payloadHeaderBytes;
    }

    /// Whether SUD pair-wise byte swap is applied while reading.
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
