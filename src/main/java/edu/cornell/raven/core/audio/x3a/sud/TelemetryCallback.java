package edu.cornell.raven.core.audio.x3a.sud;

import java.lang.foreign.MemorySegment;

/// Receives non-acoustic sensor payloads (temperature, pressure, voltage, etc.)
/// routed out of the decoder without copying onto the heap when possible.
@FunctionalInterface
public interface TelemetryCallback {

    /// @param timestamp frame timestamp associated with the telemetry chunk
    /// @param payload zero-copy slice of the mapped file containing the sensor payload
    void onTelemetry(long timestamp, MemorySegment payload);
}
