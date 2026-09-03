package edu.cornell.raven.x3a;

import edu.cornell.raven.x3a.internal.BitstreamWriter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/// Measures [X3FrameEncoder]'s per-frame cost in isolation from file I/O or container
/// framing — the encode-side counterpart to [X3FrameDecoderBenchmark].
///
/// Three signals, because the encoder picks a different code for each: a quiet tone stays in
/// RICE0/1, a mid-level tone lands in RICE3, and broadband noise forces the BFP path. All
/// three reuse one [BitstreamWriter], the way [X3BulkEncoder] drives it.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 1, time = 1)
@Fork(1)
@State(Scope.Thread)
public class X3FrameEncoderBenchmark {

    private X3FrameEncoder encoder;
    private BitstreamWriter writer;
    private int frames;

    /// Residuals of ~±1: RICE0/RICE1 territory.
    private short[] quiet;
    /// Residuals of ~±15: RICE3 territory.
    private short[] mid;
    /// Residuals well past the top threshold: BFP territory.
    private short[] noisy;
    /// Two interleaved channels of [#mid], exercising the strided residual walk.
    private short[] stereo;

    @Setup(Level.Trial)
    public void setup() {
        encoder = new X3FrameEncoder();
        frames = encoder.samplesPerFrame();
        writer = new BitstreamWriter(encoder.maxPayloadBytes(frames, 2));

        quiet = new short[frames];
        mid = new short[frames];
        noisy = new short[frames];
        stereo = new short[frames * 2];

        int state = 0x0bad_c0de;
        for (int i = 0; i < frames; i++) {
            quiet[i] = (short) (Math.sin(i * 0.001) * 200);
            mid[i] = (short) (Math.sin(i * 0.01) * 2000);
            state = state * 1_103_515_245 + 12_345;
            noisy[i] = (short) (state >>> 16);
            stereo[i * 2] = mid[i];
            stereo[i * 2 + 1] = (short) -mid[i];
        }
    }

    /// RICE0/1 path, mono.
    @Benchmark
    public void encodeFrameQuiet(Blackhole blackhole) {
        blackhole.consume(pack(quiet, 1));
    }

    /// RICE3 path, mono.
    @Benchmark
    public void encodeFrameMid(Blackhole blackhole) {
        blackhole.consume(pack(mid, 1));
    }

    /// BFP / pass-through path, mono.
    @Benchmark
    public void encodeFrameNoisy(Blackhole blackhole) {
        blackhole.consume(pack(noisy, 1));
    }

    /// RICE3 path, two interleaved channels.
    @Benchmark
    public void encodeFrameStereo(Blackhole blackhole) {
        blackhole.consume(pack(stereo, 2));
    }

    /// Packs one whole frame and returns the payload's checksum, so neither the bit packing
    /// nor the CRC fold can be dead-code eliminated.
    private int pack(short[] pcm, int channels) {
        writer.reset();
        encoder.encodeFrame(pcm, 0, frames, channels, writer);
        return writer.crc();
    }
}
