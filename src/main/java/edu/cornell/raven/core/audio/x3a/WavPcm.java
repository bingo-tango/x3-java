package edu.cornell.raven.core.audio.x3a;

import java.io.IOException;
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
        ByteBuffer pcm = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < sampleCount; i++) {
            samples[i] = pcm.getShort();
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

        ByteBuffer bb = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(fourcc("RIFF"));
        bb.putInt(riffSize);
        bb.putInt(fourcc("WAVE"));
        bb.putInt(fourcc("fmt "));
        bb.putInt(fmtChunkSize);
        bb.putShort((short) 1); // PCM
        bb.putShort((short) channels);
        bb.putInt(sampleRate);
        bb.putInt(sampleRate * channels * 2);
        bb.putShort((short) (channels * 2));
        bb.putShort((short) 16);
        bb.putInt(fourcc("data"));
        bb.putInt(dataBytes);
        for (short s : interleaved) {
            bb.putShort(s);
        }
        Files.write(path, bb.array());
    }

    private static int fourcc(String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        return (b[0] & 0xff)
                | ((b[1] & 0xff) << 8)
                | ((b[2] & 0xff) << 16)
                | ((b[3] & 0xff) << 24);
    }
}
