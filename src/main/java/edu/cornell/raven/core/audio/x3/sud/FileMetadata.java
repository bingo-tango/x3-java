package edu.cornell.raven.core.audio.x3.sud;

/**
 * Global configuration extracted from a {@code .SUD} file header (Phase 1).
 */
public final class FileMetadata {

    private final int sampleRate;
    private final int channels;
    private final int bitDepth;
    private final String deviceId;
    private final String xmlConfig;

    public FileMetadata(int sampleRate, int channels, int bitDepth, String deviceId, String xmlConfig) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitDepth = bitDepth;
        this.deviceId = deviceId;
        this.xmlConfig = xmlConfig;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int channels() {
        return channels;
    }

    public int bitDepth() {
        return bitDepth;
    }

    public String deviceId() {
        return deviceId;
    }

    public String xmlConfig() {
        return xmlConfig;
    }
}
