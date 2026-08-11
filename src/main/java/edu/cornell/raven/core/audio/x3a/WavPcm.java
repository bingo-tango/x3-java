package edu.cornell.raven.core.audio.x3a;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal 16-bit little-endian PCM WAV reader/writer for conversion utilities.
 * Not a general audio I/O library — only what {@link X3Files} needs.
 */
public final class WavPcm {

    private static final int WRITE_SLAB_SAMPLES = 32 * 1024; // 64 KiB LE bytes

    public static final class WavData {
        public final int sampleRate;
        public final int channels;
        public final int bitsPerSample;
        /** Interleaved signed PCM samples. */
        public final short[] samples;
        /** Number of frames (= samples.length / channels). */
        public final int frames;

        public WavData(int sampleRate, int channels, int bitsPerSample, short[] samples) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
            this.samples = samples;
            this.frames = samples.length / channels;
        }
    }

    private WavPcm() {
    }

    public static WavData read(Path path) throws IOException {
        byte[] all = Files.readAllBytes(path);
        if (all.length < 44) {
            throw new IOException("WAV too small: " + path);
        }
        ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        if (bb.getInt() != fourcc("RIFF")) {
            throw new IOException("not RIFF: " + path);
        }
        bb.getInt(); // riff size
        if (bb.getInt() != fourcc("WAVE")) {
            throw new IOException("not WAVE: " + path);
        }

        int sampleRate = 0;
        int channels = 0;
        int bitsPerSample = 0;
        int audioFormat = 0;
        byte[] data = null;

        while (bb.remaining() >= 8) {
            int id = bb.getInt();
            int size = bb.getInt();
            if (size < 0 || size > bb.remaining()) {
                throw new IOException("bad chunk size in " + path);
            }
            int start = bb.position();
            if (id == fourcc("fmt ")) {
                audioFormat = bb.getShort() & 0xffff;
                channels = bb.getShort() & 0xffff;
                sampleRate = bb.getInt();
                bb.getInt(); // byte rate
                bb.getShort(); // block align
                bitsPerSample = bb.getShort() & 0xffff;
            } else if (id == fourcc("data")) {
                data = new byte[size];
                bb.get(data);
            }
            bb.position(start + size + (size & 1)); // word pad
        }

        if (data == null) {
            throw new IOException("no data chunk: " + path);
        }
        if (audioFormat != 1) {
            throw new IOException("only PCM WAV supported (format=" + audioFormat + ")");
        }
        if (bitsPerSample != 16) {
            throw new IOException("only 16-bit PCM supported (bits=" + bitsPerSample + ")");
        }
        if (channels < 1) {
            throw new IOException("invalid channel count");
        }

        int sampleCount = data.length / 2;
        short[] samples = new short[sampleCount];
        // Tight LE unpack (avoid per-sample ByteBuffer virtual calls).
        for (int i = 0, bi = 0; i < sampleCount; i++, bi += 2) {
            samples[i] = (short) ((data[bi] & 0xff) | (data[bi + 1] << 8));
        }
        // Trim to whole frames
        int frames = sampleCount / channels;
        if (frames * channels != sampleCount) {
            short[] trim = new short[frames * channels];
            System.arraycopy(samples, 0, trim, 0, trim.length);
            samples = trim;
        }
        return new WavData(sampleRate, channels, bitsPerSample, samples);
    }

    /**
     * Write a 16-bit LE PCM WAV. Streams sample data in fixed slabs so peak heap
     * is header + one slab rather than a second full-size PCM byte image.
     */
    public static void write(Path path, int sampleRate, int channels, short[] interleaved) throws IOException {
        if (channels < 1) {
            throw new IllegalArgumentException("channels must be >= 1");
        }
        if (interleaved.length % channels != 0) {
            throw new IllegalArgumentException("sample count not divisible by channels");
        }
        int dataBytes = interleaved.length * 2;
        int fmtChunkSize = 16;
        int riffSize = 4 + (8 + fmtChunkSize) + (8 + dataBytes);

        byte[] hdr = new byte[44];
        putLe32(hdr, 0, fourcc("RIFF"));
        putLe32(hdr, 4, riffSize);
        putLe32(hdr, 8, fourcc("WAVE"));
        putLe32(hdr, 12, fourcc("fmt "));
        putLe32(hdr, 16, fmtChunkSize);
        putLe16(hdr, 20, 1); // PCM
        putLe16(hdr, 22, channels);
        putLe32(hdr, 24, sampleRate);
        putLe32(hdr, 28, sampleRate * channels * 2);
        putLe16(hdr, 32, channels * 2);
        putLe16(hdr, 34, 16);
        putLe32(hdr, 36, fourcc("data"));
        putLe32(hdr, 40, dataBytes);

        byte[] slab = new byte[Math.min(dataBytes, WRITE_SLAB_SAMPLES * 2)];
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(path), 64 * 1024)) {
            out.write(hdr);
            int i = 0;
            final int nSamples = interleaved.length;
            while (i < nSamples) {
                int batch = Math.min(nSamples - i, slab.length >> 1);
                int bi = 0;
                for (int s = 0; s < batch; s++) {
                    short v = interleaved[i + s];
                    slab[bi++] = (byte) v;
                    slab[bi++] = (byte) (v >> 8);
                }
                out.write(slab, 0, bi);
                i += batch;
            }
        }
    }

    private static void putLe16(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }

    private static void putLe32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static int fourcc(String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        return (b[0] & 0xff)
                | ((b[1] & 0xff) << 8)
                | ((b[2] & 0xff) << 16)
                | ((b[3] & 0xff) << 24);
    }
}
