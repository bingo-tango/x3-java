package edu.cornell.raven.core.audio.x3a.sud;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TelemetryCallbackTest {

    @Test
    void callbackReceivesTimestampAndPayload() {
        AtomicLong seenTs = new AtomicLong();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment payload = arena.allocate(8);
            TelemetryCallback callback = (timestamp, segment) -> {
                seenTs.set(timestamp);
                assertSame(payload, segment);
            };

            callback.onTelemetry(99L, payload);
            assertEquals(99L, seenTs.get());
        }
    }
}
