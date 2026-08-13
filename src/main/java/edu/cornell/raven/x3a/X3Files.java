package edu.cornell.raven.x3a;

import edu.cornell.raven.core.audio.x3a.internal.*;
import edu.cornell.raven.x3a.internal.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Simple `.wav` ↔ `.x3a` file conversion for tests and upstream tooling.
///
/// Pure Java, no libsndfile. Archive layout matches the public X3 archive format
/// (x3-rust / x3new.m): `X3ARCHIV` + XML config frame + data frames.
public final class X3Files {

    /// Below this many data frames, parallel fan-out is skipped (matches [ChunkPipeline]).
    private static final int PARALLEL_FRAME_FLOOR = 2;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private static final Pattern FS = Pattern.compile("<FS[^>]*>(\\d+)</FS>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLKLEN = Pattern.compile("<BLKLEN>(\\d+)</BLKLEN>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODES = Pattern.compile(
            "<CODES[^>]*>\\s*RICE(\\d+)\\s*,\\s*RICE(\\d+)\\s*,\\s*RICE(\\d+)\\s*,\\s*BFP\\s*</CODES>",
            Pattern.CASE_INSENSITIVE);

    private X3Files() {
    }

    /// Converts a 16-bit PCM WAV file to an X3 archive (`.x3a`). Overwrites `x3aPath` if it exists.
    public static void wavToX3a(Path wavPath, Path x3aPath) throws IOException {
        wav_to_x3a(wavPath, x3aPath);
    }

    /// Snake_case alias matching the x3-rust API name.
    public static void wav_to_x3a(Path wavPath, Path x3aPath) throws IOException {
        WavPcm.WavData wav = WavPcm.read(wavPath);
        X3AudioEncoder encoder = new X3AudioEncoder();
        byte[] archive = encodeArchive(
                wav.samples,
                wav.frames,
                wav.channels,
                wav.sampleRate,
                encoder);
        Files.write(x3aPath, archive);
    }

    /// Converts an X3 archive (`.x3a`) to a 16-bit PCM WAV file. Overwrites `wavPath` if it exists.
    public static void x3aToWav(Path x3aPath, Path wavPath) throws IOException {
        x3a_to_wav(x3aPath, wavPath);
    }

    /// Snake_case alias matching the x3-rust API name.
    public static void x3a_to_wav(Path x3aPath, Path wavPath) throws IOException {
        byte[] all = Files.readAllBytes(x3aPath);
        DecodedArchive dec = decodeArchive(all);
        WavPcm.write(wavPath, dec.sampleRate, dec.channels, dec.pcm);
    }

    /// Builds a complete `.x3a` byte image from interleaved PCM.
    public static byte[] encodeArchive(short[] pcm, int frames, int channels, int sampleRate,
                                       X3AudioEncoder encoder) {
        if (frames <= 0) {
            throw new IllegalArgumentException("frames must be > 0");
        }
        if (pcm.length < frames * channels) {
            throw new IllegalArgumentException("pcm too short");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(pcm.length + 1024);
        try {
            out.write(X3FrameHeader.ARCHIVE_ID);

            String xml = buildXml(sampleRate, encoder);
            byte[] xmlBytes = xml.getBytes(StandardCharsets.US_ASCII);
            // Even length payload (word align): trim one pad space if odd.
            int xmlLen = xmlBytes.length;
            if ((xmlLen & 1) != 0) {
                xmlLen--;
            }
            int xmlCrc = Crc16.crc(xmlBytes, 0, xmlLen);
            // Archive header frame: source 0, channels 0, samples 0 (metadata).
            out.write(new X3FrameHeader(0, 0, 0, xmlLen, 0L, xmlCrc).encode());
            out.write(xmlBytes, 0, xmlLen);

            int spf = encoder.samplesPerFrame();
            int offFrames = 0;
            while (offFrames < frames) {
                int n = Math.min(spf, frames - offFrames);
                int sampleOff = offFrames * channels;
                BitstreamWriter bp = encoder.encodeFrame(pcm, sampleOff, n, channels);
                byte[] payload = bp.toByteArray();
                long timeNanos = offFrames * NANOS_PER_SECOND / sampleRate;
                out.write(new X3FrameHeader(1, channels, n, payload.length, timeNanos, bp.crc()).encode());
                out.write(payload);
                offFrames += n;
            }
        } catch (IOException e) {
            // ByteArrayOutputStream does not throw
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /// Decodes a full archive image to interleaved PCM + stream metadata, using
    /// [DecodeOptions#defaults()] for frame-decode concurrency.
    public static DecodedArchive decodeArchive(byte[] archive) {
        return decodeArchive(archive, DecodeOptions.defaults());
    }

    /// Decodes a full archive image to interleaved PCM + stream metadata.
    ///
    /// Uses a single pre-sized PCM buffer; frame payloads are zero-copy slices of the
    /// archive array, avoiding a per-frame arena or chunk list. Data frames are
    /// independent (each carries its own filter state), so above
    /// `options.maxConcurrency() > 1` they decode in parallel on virtual threads, gated
    /// by a local + optional shared [Semaphore] (mirrors [ChunkPipeline#decodeWindowInt]).
    public static DecodedArchive decodeArchive(byte[] archive, DecodeOptions options) {
        if (archive.length < X3FrameHeader.ARCHIVE_ID.length + X3FrameHeader.LENGTH) {
            throw new IllegalArgumentException("archive too small");
        }
        for (int i = 0; i < X3FrameHeader.ARCHIVE_ID.length; i++) {
            if (archive[i] != X3FrameHeader.ARCHIVE_ID[i]) {
                throw new IllegalArgumentException("missing X3ARCHIV id");
            }
        }

        int pos = X3FrameHeader.ARCHIVE_ID.length;
        X3FrameHeader xmlHdr = X3FrameHeader.decode(archive, pos);
        pos += X3FrameHeader.LENGTH;
        if (pos + xmlHdr.payloadLen > archive.length) {
            throw new IllegalArgumentException("XML payload truncated");
        }
        int gotCrc = Crc16.crc(archive, pos, xmlHdr.payloadLen);
        if (gotCrc != xmlHdr.payloadCrc) {
            throw new IllegalArgumentException("XML payload CRC mismatch");
        }
        String xml = new String(archive, pos, xmlHdr.payloadLen, StandardCharsets.US_ASCII);
        pos += xmlHdr.payloadLen;

        int sampleRate = parseInt(FS, xml, 48000);
        int blockLen = parseInt(BLKLEN, xml, X3AudioEncoder.DEFAULT_BLOCK_LEN);
        int[] rice = parseTriple(CODES, xml, X3AudioEncoder.DEFAULT_RICE_ORDERS);
        X3AudioDecoder decoder = new X3AudioDecoder(blockLen, rice);

        // Single pass: verify header CRC (via X3FrameHeader.decode) and payload CRC exactly
        // once per frame — including metadata frames, matching the original two-pass
        // semantics — while recording data-frame descriptors for decode.
        int channels = 1;
        int frameCount = 0;
        int[] payloadOffset = new int[16];
        int[] payloadLen = new int[16];
        int[] sampleCount = new int[16];
        int[] frameChannels = new int[16];
        int[] pcmOffset = new int[16];

        int totalSamples = 0;
        while (pos + X3FrameHeader.LENGTH <= archive.length) {
            int key = X3FrameHeader.getBe16(archive, pos);
            if (key != X3FrameHeader.KEY) {
                break;
            }
            X3FrameHeader fh = X3FrameHeader.decode(archive, pos);
            pos += X3FrameHeader.LENGTH;
            if (fh.payloadLen <= 0 || pos + fh.payloadLen > archive.length) {
                break;
            }
            int pcrc = Crc16.crc(archive, pos, fh.payloadLen);
            if (pcrc != fh.payloadCrc) {
                throw new IllegalArgumentException("frame payload CRC mismatch at " + (pos - X3FrameHeader.LENGTH));
            }

            if (fh.samples > 0) {
                channels = Math.max(1, fh.channels);
                int n = fh.samples * channels;
                if (frameCount == payloadOffset.length) {
                    int cap = payloadOffset.length * 2;
                    payloadOffset = Arrays.copyOf(payloadOffset, cap);
                    payloadLen = Arrays.copyOf(payloadLen, cap);
                    sampleCount = Arrays.copyOf(sampleCount, cap);
                    frameChannels = Arrays.copyOf(frameChannels, cap);
                    pcmOffset = Arrays.copyOf(pcmOffset, cap);
                }
                payloadOffset[frameCount] = pos;
                payloadLen[frameCount] = fh.payloadLen;
                sampleCount[frameCount] = fh.samples;
                frameChannels[frameCount] = channels;
                pcmOffset[frameCount] = totalSamples;
                frameCount++;
                totalSamples += n;
            }
            pos += fh.payloadLen;
        }

        short[] pcm = totalSamples > 0 ? new short[totalSamples] : new short[0];

        if (frameCount < PARALLEL_FRAME_FLOOR || options.maxConcurrency() == 1) {
            for (int i = 0; i < frameCount; i++) {
                decoder.decodeChunkInt(archive, payloadOffset[i], payloadLen[i], sampleCount[i],
                        frameChannels[i], pcm, pcmOffset[i], false);
            }
        } else {
            decodeFramesParallel(archive, decoder, frameCount, payloadOffset, payloadLen,
                    sampleCount, frameChannels, pcmOffset, pcm, options);
        }

        return new DecodedArchive(sampleRate, channels, pcm, xml);
    }

    private static void decodeFramesParallel(byte[] archive, X3AudioDecoder decoder, int frameCount,
            int[] payloadOffset, int[] payloadLen, int[] sampleCount, int[] frameChannels,
            int[] pcmOffset, short[] pcm, DecodeOptions options) {
        int maxConcurrency = Math.max(1, options.maxConcurrency());
        Semaphore localLimiter = new Semaphore(maxConcurrency, false);
        Semaphore sharedLimiter = options.useSharedLimiter() ? options.sharedLimiter() : null;

        List<Callable<Void>> tasks = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            final int idx = i;
            tasks.add(() -> {
                acquirePermits(localLimiter, sharedLimiter);
                try {
                    // Task-local decoder: X3AudioDecoder keeps block scratch on the instance.
                    X3AudioDecoder local = decoder.newInstance();
                    local.decodeChunkInt(archive, payloadOffset[idx], payloadLen[idx], sampleCount[idx],
                            frameChannels[idx], pcm, pcmOffset[idx], false);
                    return null;
                } finally {
                    releasePermits(localLimiter, sharedLimiter);
                }
            });
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during parallel archive decode", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("parallel archive decode failed", cause);
        }
    }

    private static void acquirePermits(Semaphore localLimiter, Semaphore sharedLimiter) throws InterruptedException {
        localLimiter.acquire();
        if (sharedLimiter != null) {
            try {
                sharedLimiter.acquire();
            } catch (InterruptedException | RuntimeException e) {
                localLimiter.release();
                throw e;
            }
        }
    }

    private static void releasePermits(Semaphore localLimiter, Semaphore sharedLimiter) {
        if (sharedLimiter != null) {
            sharedLimiter.release();
        }
        localLimiter.release();
    }

    /// Result of [#decodeArchive(byte[])]: interleaved PCM plus the stream metadata
    /// recovered from the archive's config frame.
    public static final class DecodedArchive {
        /// Sample rate in Hz, parsed from the embedded `<FS>` config.
        public final int sampleRate;
        /// Channel count, taken from the first data frame's header.
        public final int channels;
        /// Interleaved decoded PCM samples.
        public final short[] pcm;
        /// Embedded `<X3ARCH>`/`<CFG>` config XML, recovered verbatim from the archive.
        public final String xml;

        public DecodedArchive(int sampleRate, int channels, short[] pcm, String xml) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.pcm = pcm;
            this.xml = xml;
        }

        /// `pcm.length / channels`.
        public int frames() {
            return pcm.length / channels;
        }
    }

    static String buildXml(int sampleRate, X3AudioEncoder enc) {
        int[] c = enc.riceOrders();
        int[] t = enc.thresholds();
        // Trailing space keeps common layouts even-length after concat.
        return "<X3ARCH PROG=\"x3-java\" VERSION=\"2.0\" />"
                + "<CFG ID=\"0\" FTYPE=\"XML\" />"
                + "<CFG ID=\"1\" FTYPE=\"WAV\">"
                + "<FS UNIT=\"Hz\">" + sampleRate + "</FS>"
                + "<SUFFIX>wav</SUFFIX>"
                + "<CODEC TYPE=\"X3\" VERS=\"2\">"
                + "<BLKLEN>" + enc.blockLen() + "</BLKLEN>"
                + "<CODES N=\"4\">RICE" + c[0] + ",RICE" + c[1] + ",RICE" + c[2] + ",BFP</CODES>"
                + "<FILTER>DIFF</FILTER>"
                + "<NBITS>16</NBITS>"
                + "<T N=\"3\">" + t[0] + "," + t[1] + "," + t[2] + "</T>"
                + "</CODEC>"
                + "</CFG>"
                + " ";
    }

    private static int parseInt(Pattern p, String xml, int def) {
        Matcher m = p.matcher(xml);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return def;
    }

    private static int[] parseTriple(Pattern p, String xml, int[] def) {
        Matcher m = p.matcher(xml);
        if (m.find()) {
            return new int[] {
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))
            };
        }
        return def;
    }
}
