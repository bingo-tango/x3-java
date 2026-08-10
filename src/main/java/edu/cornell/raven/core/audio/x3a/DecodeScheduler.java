package edu.cornell.raven.core.audio.x3a;

import java.util.concurrent.Semaphore;

/**
 * Process-wide throttle for in-flight X3 chunk decode work.
 * <p>
 * Virtual threads are cheap; this limits how many decode tasks run at once so multiple
 * decoders (and the host app) do not oversubscribe carrier CPUs. Defaults are conservative
 * for library embedding: {@code min(8, max(1, cores/2))}.
 */
public final class DecodeScheduler {

    private static final String PROP_SHARED = "x3a.decode.sharedMaxConcurrency";

    private static final Semaphore SHARED = new Semaphore(defaultSharedConcurrency(), false);

    private DecodeScheduler() {
    }

    /**
     * Default process-wide budget: {@code min(8, max(1, availableProcessors() / 2))}.
     */
    public static int defaultSharedConcurrency() {
        int fromProp = positiveIntProperty(PROP_SHARED, -1);
        if (fromProp > 0) {
            return fromProp;
        }
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.clamp(cores / 2, 1, 8);
    }

    /**
     * Default per-decoder fan-out: {@code min(4, max(1, availableProcessors()))}.
     */
    public static int defaultPerDecoderConcurrency() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.clamp(cores / 2, 1, 4);
    }

    /**
     * Shared limiter used when {@link DecodeOptions#useSharedLimiter()} is true.
     */
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
