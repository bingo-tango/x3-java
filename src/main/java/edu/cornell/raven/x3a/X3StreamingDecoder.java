package edu.cornell.raven.x3a;

import java.io.IOException;

/// Random-access read handle over one X3-coded audio file, independent of the container
/// it arrived in.
///
/// Implemented by [X3ArchiveStreamingDecoder] (bare `.x3a` archives) and
/// [edu.cornell.raven.x3a.sud.SudStreamingDecoder] (SoundTrap `.SUD` containers), so hosts
/// that only need PCM can treat both identically — see [X3Streams#open].
///
/// This is the *streaming* half of the API: bounded windows, no whole-file buffer. The bulk
/// half is [X3BulkDecoder], which decodes an entire archive into one array.
///
/// Both decode methods write into a caller-owned buffer and return the frame count actually
/// produced, so a steady-state read loop allocates nothing. A request past the end of the
/// file is clamped rather than failing, and returns fewer frames than asked for.
public interface X3StreamingDecoder extends AutoCloseable {

    /// [#deviceId()] for containers that don't identify their recorder.
    String UNKNOWN_DEVICE_ID = "UNKNOWN";

    /// Sample rate in Hz.
    int sampleRate();

    /// Channel count; decoded samples are interleaved by this stride.
    int channels();

    /// Bits per sample in the decoded PCM. X3 always codes 16-bit.
    int bitDepth();

    /// Total frames (samples per channel) available to [#decodeSamplesInt].
    long totalSamples();

    /// Recording device identifier, or [#UNKNOWN_DEVICE_ID] when the container carries no
    /// device tag — bare `.x3a` archives don't, `.SUD` metadata records do.
    default String deviceId() {
        return UNKNOWN_DEVICE_ID;
    }

    /// Decodes `length` frames starting at `startSample` into `dest` as interleaved 16-bit
    /// PCM, requiring `length * channels()` capacity from index 0.
    ///
    /// @return frames written, `0` if `startSample` is at or past [#totalSamples]
    /// @throws X3FormatException if the coded data for the requested window is malformed
    /// @throws IOException if the underlying file cannot be read
    int decodeSamplesInt(long startSample, int length, short[] dest) throws IOException;

    /// Same as [#decodeSamplesInt] but normalized to `[-1, 1)` floats.
    ///
    /// @return frames written
    /// @throws X3FormatException if the coded data for the requested window is malformed
    /// @throws IOException if the underlying file cannot be read
    int decodeSamplesFloat(long startSample, int length, float[] dest) throws IOException;

    /// Releases the underlying file mapping. Idempotent.
    @Override
    void close();
}
