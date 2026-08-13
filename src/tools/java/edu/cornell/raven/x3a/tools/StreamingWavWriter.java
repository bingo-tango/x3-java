package edu.cornell.raven.x3a.tools;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/// Streaming 16-bit LE PCM RIFF/WAVE writer for the verification app.
///
/// Writes a 44-byte header with placeholder sizes, appends PCM as each decode
/// window arrives via [#writeFrames], and back-patches the RIFF/data chunk
/// sizes on [#close()]. Unlike `WavPcm.write` (which requires the
/// whole PCM buffer up front), this lets the caller stream a `.SUD` file's
/// windowed decode loop directly to disk without buffering the entire file.
///
/// Test scaffolding only — not the production encode path
final class StreamingWavWriter implements Closeable {

    private static final int SLAB_FRAMES = 32 * 1024;

    private final RandomAccessFile file;
    private final int channels;
    private final byte[] slab;
    private long totalDataBytes;

    StreamingWavWriter(Path path, int sampleRate, int channels) throws IOException {
        if (channels < 1) {
            throw new IllegalArgumentException("channels must be >= 1");
        }
        this.channels = channels;
        this.slab = new byte[SLAB_FRAMES * channels * 2];
        this.file = new RandomAccessFile(path.toFile(), "rw");
        file.setLength(0);
        writeHeader(sampleRate, channels, 0L);
    }

    /** Appends {@code frameCount} frames ({@code frameCount * channels} samples) from index 0. */
    void writeFrames(short[] interleaved, int frameCount) throws IOException {
        int samples = frameCount * channels;
        int i = 0;
        while (i < samples) {
            int batch = Math.min(samples - i, slab.length >> 1);
            int bi = 0;
            for (int s = 0; s < batch; s++) {
                short v = interleaved[i + s];
                slab[bi++] = (byte) v;
                slab[bi++] = (byte) (v >> 8);
            }
            file.write(slab, 0, bi);
            i += batch;
        }
        totalDataBytes += (long) samples * 2;
    }

    @Override
    public void close() throws IOException {
        long riffSize = 36 + totalDataBytes;
        file.seek(4);
        writeLe32(riffSize);
        file.seek(40);
        writeLe32(totalDataBytes);
        file.close();
    }

    private void writeHeader(int sampleRate, int channels, long dataBytes) throws IOException {
        file.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeLe32(36 + dataBytes);
        file.write("WAVE".getBytes(StandardCharsets.US_ASCII));
        file.write("fmt ".getBytes(StandardCharsets.US_ASCII));
        writeLe32(16);
        writeLe16(1); // PCM
        writeLe16(channels);
        writeLe32(sampleRate);
        writeLe32((long) sampleRate * channels * 2);
        writeLe16(channels * 2);
        writeLe16(16);
        file.write("data".getBytes(StandardCharsets.US_ASCII));
        writeLe32(dataBytes);
    }

    private void writeLe16(int v) throws IOException {
        file.write(v & 0xff);
        file.write((v >> 8) & 0xff);
    }

    private void writeLe32(long v) throws IOException {
        file.write((int) (v & 0xff));
        file.write((int) ((v >> 8) & 0xff));
        file.write((int) ((v >> 16) & 0xff));
        file.write((int) ((v >> 24) & 0xff));
    }
}
