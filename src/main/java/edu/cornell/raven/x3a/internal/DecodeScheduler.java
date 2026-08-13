package edu.cornell.raven.x3a.internal;

import java.util.concurrent.Semaphore;

/// Process-wide throttle for in-flight X3 chunk decode work.
///
/// Virtual threads are cheap to spawn, so nothing else caps concurrency; this exists so
/// multiple decoders in one process (or the host app around them) don't oversubscribe
/// carrier-thread CPUs by all fanning out at once.
public final class DecodeScheduler {

    private static final String PROP_SHARED = "x3a.decode.sharedMaxConcurrency";

    private static final Semaphore SHARED = new Semaphore(defaultSharedConcurrency(), false);

    private DecodeScheduler() {
    }

    /// Default process-wide budget: `min(8, max(1, availableProcessors() / 2))`.
    public static int defaultSharedConcurrency() {
        int fromProp = positiveIntProperty(PROP_SHARED, -1);
        if (fromProp > 0) {
            return fromProp;
        }
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.clamp(cores / 2, 1, 8);
    }

    /// Default per-decoder fan-out: `min(4, max(1, availableProcessors()))`.
    public static int defaultPerDecoderConcurrency() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.clamp(cores / 2, 1, 4);
    }

    /// Shared limiter used when [DecodeOptions#useSharedLimiter()] is true.
    public static Semaphore sharedLimiter() {
        return SHARED;
    }

    static int positiveIntProperty(String key, int defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
