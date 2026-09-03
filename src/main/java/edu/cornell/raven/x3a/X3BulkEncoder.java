package edu.cornell.raven.x3a;

import edu.cornell.raven.x3a.internal.BitstreamWriter;
import edu.cornell.raven.x3a.internal.Crc16;
import edu.cornell.raven.x3a.internal.DecodeScheduler;
import edu.cornell.raven.x3a.internal.X3FrameHeader;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/// Whole-archive encode: interleaved PCM in, a complete `.x3a` byte image out.
///
/// The encode-side counterpart to [X3BulkDecoder], and the layer above [X3FrameEncoder] —
/// this class owns the container (the `X3ARCHIV` id, the XML config frame, and one data frame
/// per `samplesPerFrame` block); the frame encoder owns the bitstream inside each payload.
///
/// Frames are self-contained (each opens with its channels' raw filter state), which is what
/// lets [edu.cornell.raven.x3a.internal.ChunkPipeline] decode them in parallel — so they are
/// encoded in parallel here too, on virtual threads, in batches that keep the emitted frame
/// order deterministic and scratch memory bounded. Payload bytes are packed once and copied
/// once, straight into their final position in the output.
///
/// Output layout matches the public X3 archive format (x3-rust / `x3new.m`), so archives are
/// interchangeable with the reference tools.
public final class X3BulkEncoder {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /// Overrides the default encode fan-out; see [#maxConcurrency()].
    private static final String PROP_CONCURRENCY = "x3a.encode.maxConcurrency";

    /// Below this many frames, parallel fan-out is skipped — task setup would cost more than
    /// the sequential encode it replaces. Mirrors `ChunkPipeline`'s decode-side floor.
    private static final int PARALLEL_FRAME_FLOOR = 2;

    /// In-flight frames per encode, as a multiple of [#maxConcurrency()] — enough slack to
    /// keep every worker fed across a batch boundary without holding the whole file's
    /// payloads at once.
    private static final int SLOTS_PER_WORKER = 2;

    private X3BulkEncoder() {
    }

    /// Frames encoded concurrently: the `x3a.encode.maxConcurrency` system property when set,
    /// else `clamp(availableProcessors() / 2, 1, 4)` — the same shape as
    /// [DecodeScheduler#defaultPerDecoderConcurrency()], so encode and decode fan out alike.
    public static int maxConcurrency() {
        int fromProp = DecodeScheduler.positiveIntProperty(PROP_CONCURRENCY, -1);
        if (fromProp > 0) {
            return fromProp;
        }
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
    }

    /// Builds a complete `.x3a` byte image from interleaved PCM, using [X3FrameEncoder]'s
    /// defaults.
    public static byte[] encode(short[] pcm, int frames, int channels, int sampleRate) {
        return encode(pcm, frames, channels, sampleRate, new X3FrameEncoder());
    }

    /// Builds a complete `.x3a` byte image from interleaved PCM.
    ///
    /// The encoder's block length, rice orders, and thresholds are written into the archive's
    /// XML config frame, so [X3BulkDecoder] recovers them without out-of-band agreement.
    ///
    /// Prefer [#encodeTo] when the archive is headed for a file or socket: it needs no
    /// whole-image buffer at all.
    ///
    /// @param pcm        interleaved samples, at least `frames * channels` long
    /// @param frames     samples per channel to encode
    /// @param channels   channel count
    /// @param sampleRate sample rate in Hz, recorded as the config frame's `<FS>`
    /// @param encoder    frame codec supplying the coding parameters
    public static byte[] encode(short[] pcm, int frames, int channels, int sampleRate,
                                X3FrameEncoder encoder) {
        validate(pcm, frames, channels);
        // Sized for a ~2:1 archive, the usual X3 ratio; the sink doubles if a noisier
        // recording overruns it.
        int frameCount = frameCount(frames, encoder);
        long estimate = 1024L + (long) frameCount * X3FrameHeader.LENGTH + (long) frames * channels;
        ArraySink sink = new ArraySink((int) Math.min(estimate, Integer.MAX_VALUE - 8));
        try {
            encodeFrames(sink, pcm, frames, channels, sampleRate, encoder);
        } catch (IOException e) {
            // ArraySink never throws.
            throw new IllegalStateException(e);
        }
        return sink.toArray();
    }

    /// Streams a complete `.x3a` image to `out`, using [X3FrameEncoder]'s defaults.
    public static void encodeTo(OutputStream out, short[] pcm, int frames, int channels, int sampleRate)
            throws IOException {
        encodeTo(out, pcm, frames, channels, sampleRate, new X3FrameEncoder());
    }

    /// Streams a complete `.x3a` image to `out` as each frame is packed, so peak heap is the
    /// in-flight frame slots rather than the whole archive.
    ///
    /// Otherwise identical to [#encode(short[],int,int,int,X3FrameEncoder)] — same bytes, same
    /// order. `out` is neither buffered nor closed here; wrap it if it needs buffering.
    public static void encodeTo(OutputStream out, short[] pcm, int frames, int channels, int sampleRate,
                                X3FrameEncoder encoder) throws IOException {
        if (out == null) {
            throw new IllegalArgumentException("out must not be null");
        }
        validate(pcm, frames, channels);
        encodeFrames(new StreamSink(out), pcm, frames, channels, sampleRate, encoder);
    }

    private static void validate(short[] pcm, int frames, int channels) {
        if (frames <= 0) {
            throw new IllegalArgumentException("frames must be > 0");
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be > 0");
        }
        if (pcm.length < (long) frames * channels) {
            throw new IllegalArgumentException("pcm too short");
        }
    }

    private static int frameCount(int frames, X3FrameEncoder encoder) {
        int spf = encoder.samplesPerFrame();
        return (frames + spf - 1) / spf;
    }

    /// Writes the container prefix, then every data frame, through `sink`.
    private static void encodeFrames(FrameSink sink, short[] pcm, int frames, int channels,
                                     int sampleRate, X3FrameEncoder encoder) throws IOException {
        writePrefix(sink, sampleRate, encoder);

        int frameCount = frameCount(frames, encoder);
        int concurrency = maxConcurrency();
        if (frameCount < PARALLEL_FRAME_FLOOR || concurrency <= 1) {
            encodeSequential(sink, pcm, frames, channels, sampleRate, encoder);
        } else {
            encodeParallel(sink, pcm, frames, channels, sampleRate, encoder, frameCount, concurrency);
        }
    }

    /// Archive id + the XML config frame describing the coding parameters.
    private static void writePrefix(FrameSink sink, int sampleRate, X3FrameEncoder encoder)
            throws IOException {
        byte[] xmlBytes = buildXml(sampleRate, encoder).getBytes(StandardCharsets.US_ASCII);
        // Even length payload (word align): trim one pad space if odd.
        int xmlLen = xmlBytes.length & ~1;
        int total = X3FrameHeader.ARCHIVE_ID.length + X3FrameHeader.LENGTH + xmlLen;

        int off = sink.claim(total);
        byte[] dest = sink.buffer();
        System.arraycopy(X3FrameHeader.ARCHIVE_ID, 0, dest, off, X3FrameHeader.ARCHIVE_ID.length);
        int headerOff = off + X3FrameHeader.ARCHIVE_ID.length;
        // Archive header frame: source 0, channels 0, samples 0 (metadata).
        new X3FrameHeader(0, 0, 0, xmlLen, 0L, Crc16.crc(xmlBytes, 0, xmlLen))
                .encodeInto(dest, headerOff);
        System.arraycopy(xmlBytes, 0, dest, headerOff + X3FrameHeader.LENGTH, xmlLen);
        sink.commit(total);
    }

    private static void encodeSequential(FrameSink sink, short[] pcm, int frames, int channels,
                                         int sampleRate, X3FrameEncoder encoder) throws IOException {
        int spf = encoder.samplesPerFrame();
        BitstreamWriter bp = new BitstreamWriter(encoder.maxPayloadBytes(spf, channels));
        int offFrames = 0;
        while (offFrames < frames) {
            int n = Math.min(spf, frames - offFrames);
            bp.reset();
            encoder.encodeFrame(pcm, offFrames * channels, n, channels, bp);
            emitFrame(sink, bp, channels, n, timeNanos(offFrames, sampleRate));
            offFrames += n;
        }
    }

    /// Encodes `slots` frames at a time on virtual threads, then emits that batch in frame
    /// order. Writers and per-thread encoders are allocated once and reused across batches, so
    /// the steady state allocates nothing per frame beyond the task objects themselves.
    private static void encodeParallel(FrameSink sink, short[] pcm, int frames, int channels,
                                       int sampleRate, X3FrameEncoder encoder,
                                       int frameCount, int concurrency) throws IOException {
        int spf = encoder.samplesPerFrame();
        int slots = Math.min(frameCount, Math.max(PARALLEL_FRAME_FLOOR, concurrency * SLOTS_PER_WORKER));
        int slotCapacity = encoder.maxPayloadBytes(spf, channels);

        BitstreamWriter[] writers = new BitstreamWriter[slots];
        X3FrameEncoder[] encoders = new X3FrameEncoder[slots];
        for (int s = 0; s < slots; s++) {
            writers[s] = new BitstreamWriter(slotCapacity);
            // Per-slot encoder: X3FrameEncoder keeps residual scratch on the instance.
            encoders[s] = encoder.newInstance();
        }

        Semaphore limiter = new Semaphore(concurrency, false);
        List<Callable<Void>> tasks = new ArrayList<>(slots);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            int frameIndex = 0;
            while (frameIndex < frameCount) {
                int batch = Math.min(slots, frameCount - frameIndex);
                tasks.clear();
                for (int s = 0; s < batch; s++) {
                    final int slot = s;
                    final int offFrames = (frameIndex + s) * spf;
                    final int n = Math.min(spf, frames - offFrames);
                    tasks.add(() -> {
                        limiter.acquire();
                        try {
                            BitstreamWriter bp = writers[slot];
                            bp.reset();
                            encoders[slot].encodeFrame(pcm, offFrames * channels, n, channels, bp);
                            return null;
                        } finally {
                            limiter.release();
                        }
                    });
                }
                await(executor.invokeAll(tasks));
                for (int s = 0; s < batch; s++) {
                    int offFrames = (frameIndex + s) * spf;
                    int n = Math.min(spf, frames - offFrames);
                    emitFrame(sink, writers[s], channels, n, timeNanos(offFrames, sampleRate));
                }
                frameIndex += batch;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during parallel encode", e);
        }
    }

    /// Joins one batch, surfacing the first failing frame's cause. Frame tasks only pack bits —
    /// all I/O happens on the emitting thread — so nothing checked can arrive here.
    private static void await(List<Future<Void>> futures) throws InterruptedException {
        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException("parallel encode failed", cause);
            }
        }
    }

    /// Emits one frame header plus its packed payload, claiming both as a single run so the
    /// payload is copied straight from the writer into its final position.
    private static void emitFrame(FrameSink sink, BitstreamWriter bp, int channels, int samples,
                                 long timeNanos) throws IOException {
        int payloadLen = bp.byteLength();
        int total = X3FrameHeader.LENGTH + payloadLen;
        int off = sink.claim(total);
        byte[] dest = sink.buffer();
        new X3FrameHeader(1, channels, samples, payloadLen, timeNanos, bp.crc()).encodeInto(dest, off);
        bp.copyTo(dest, off + X3FrameHeader.LENGTH);
        sink.commit(total);
    }

    private static long timeNanos(int offFrames, int sampleRate) {
        return offFrames * NANOS_PER_SECOND / sampleRate;
    }

    /// Claim/commit destination for encoded bytes.
    ///
    /// Callers reserve a run, write into [#buffer()] at the returned offset, then commit — so
    /// a heap archive can hand out its own storage and take the packed bytes with no
    /// intermediate copy, while a stream hands out reusable scratch.
    private interface FrameSink {

        /// Reserves `need` writable bytes and returns the offset to write them at. Invalidates
        /// any buffer reference from an earlier claim; call [#buffer()] after this.
        int claim(int need) throws IOException;

        /// Destination for the current claim.
        byte[] buffer();

        /// Publishes `used` bytes (at most the claimed count) from the current claim.
        void commit(int used) throws IOException;
    }

    /// Builds the archive in one growing heap array; frames land in their final position.
    private static final class ArraySink implements FrameSink {

        private byte[] buf;
        private int len;
        private int claimAt;

        ArraySink(int initialCapacity) {
            this.buf = new byte[Math.max(64, initialCapacity)];
        }

        @Override
        public int claim(int need) {
            long required = (long) len + need;
            if (required > buf.length) {
                if (required > Integer.MAX_VALUE - 8) {
                    throw new IllegalStateException("archive too large for one byte[]: "
                            + required + " bytes; use X3BulkEncoder.encodeTo instead");
                }
                int n = buf.length;
                while (n < required) {
                    n = (int) Math.min((long) n * 2, Integer.MAX_VALUE - 8);
                }
                buf = Arrays.copyOf(buf, n);
            }
            claimAt = len;
            return claimAt;
        }

        @Override
        public byte[] buffer() {
            return buf;
        }

        @Override
        public void commit(int used) {
            len += used;
        }

        byte[] toArray() {
            return len == buf.length ? buf : Arrays.copyOf(buf, len);
        }
    }

    /// Writes each committed run straight through to a stream, reusing one scratch buffer.
    private static final class StreamSink implements FrameSink {

        private final OutputStream out;
        private byte[] scratch = new byte[0];

        StreamSink(OutputStream out) {
            this.out = out;
        }

        @Override
        public int claim(int need) {
            if (scratch.length < need) {
                scratch = new byte[need];
            }
            return 0;
        }

        @Override
        public byte[] buffer() {
            return scratch;
        }

        @Override
        public void commit(int used) throws IOException {
            out.write(scratch, 0, used);
        }
    }

    /// The archive's XML config frame, describing the coding parameters `encoder` will use so a
    /// decoder can reconstruct them.
    static String buildXml(int sampleRate, X3FrameEncoder enc) {
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
}
