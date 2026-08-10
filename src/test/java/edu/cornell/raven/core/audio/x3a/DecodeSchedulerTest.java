package edu.cornell.raven.core.audio.x3a;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecodeSchedulerTest {

    @Test
    void defaultPerDecoder_isAtMostFour() {
        int n = DecodeScheduler.defaultPerDecoderConcurrency();
        int cores = Runtime.getRuntime().availableProcessors();
        assertTrue(n >= 1);
        assertEquals(Math.min(4, cores), n);
    }

    @Test
    void defaultShared_isAtMostEight() {
        int n = DecodeScheduler.defaultSharedConcurrency();
        int cores = Runtime.getRuntime().availableProcessors();
        assertTrue(n >= 1);
        assertEquals(Math.max(1, Math.min(8, cores / 2)), n);
    }

    @Test
    void defaults_enableSharedLimiter() {
        DecodeOptions opts = DecodeOptions.defaults();
        assertTrue(opts.useSharedLimiter());
        assertTrue(opts.maxConcurrency() >= 1 && opts.maxConcurrency() <= 4);
    }
}
