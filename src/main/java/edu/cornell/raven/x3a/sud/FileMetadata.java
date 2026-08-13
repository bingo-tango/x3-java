package edu.cornell.raven.x3a.sud;

/// Global device/audio configuration recovered from a `.SUD` file's metadata records,
/// separate from [SudFileMapper] itself so it can be handed to consumers (e.g. output
/// WAV headers) without exposing the mapped file.
public final class FileMetadata {

    private final int sampleRate;
    private final int channels;
    private final int bitDepth;
    private final String deviceId;
    private final String xmlConfig;

    /// Wraps already-recovered configuration values; callers are [SudFileMapper] and tests.
    ///
    /// @param xmlConfig full recovered `<SUD_METADATA>` document; see [SudFileMapper]
    public FileMetadata(int sampleRate, int channels, int bitDepth, String deviceId, String xmlConfig) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitDepth = bitDepth;
        this.deviceId = deviceId;
        this.xmlConfig = xmlConfig;
    }

    /// Sample rate in Hz.
    public int sampleRate() {
        return sampleRate;
    }

    /// Channel count.
    public int channels() {
        return channels;
    }

    /// Bits per sample.
    public int bitDepth() {
        return bitDepth;
    }

    /// Device identifier recovered from the `HARDWARE_ID` metadata tag, or `"UNKNOWN"`.
    public String deviceId() {
        return deviceId;
    }

    /// Full recovered metadata XML document; see [SudFileMapper]'s class doc.
    public String xmlConfig() {
        return xmlConfig;
    }
}
