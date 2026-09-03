package edu.cornell.raven.x3a;

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

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/// Measures whole-archive encode — container framing plus the parallel frame fan-out — against
/// the same PCM decoded by [X3BulkDecoder], so the two directions are directly comparable.
///
/// [#encodeSequential] pins the fan-out to one worker, which is what makes the parallel
/// speedup (and any regression in it) visible rather than inferred.
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 2)
@Measurement(iterations = 1, time = 2)
@Fork(1)
@State(Scope.Thread)
public class X3BulkEncoderBenchmark {

    /// ~10 s of 96 kHz mono — enough frames (96 at the default 10 000 per frame) for fan-out
    /// to matter, small enough to stay in cache-friendly territory.
    private static final int FRAMES = 960_000;
    private static final int SAMPLE_RATE = 96_000;

    private short[] pcm;
    private X3FrameEncoder encoder;
    private OutputStream discard;

    @Setup(Level.Trial)
    public void setup() {
        encoder = new X3FrameEncoder();
        pcm = new short[FRAMES];
        // Tone plus a little noise: mostly Rice-coded with occasional BFP blocks, like a real
        // recording — an all-quiet buffer would flatter the Rice path unrealistically.
        int state = 0x5eed_1234;
        for (int i = 0; i < FRAMES; i++) {
            state = state * 1_103_515_245 + 12_345;
            pcm[i] = (short) (Math.sin(i * 0.002) * 6000 + ((state >> 22) & 0x3f));
        }
        discard = OutputStream.nullOutputStream();
    }

    /// Whole-archive encode into a heap image, with the default fan-out.
    @Benchmark
    public void encodeToArray(Blackhole blackhole) {
        blackhole.consume(X3BulkEncoder.encode(pcm, FRAMES, 1, SAMPLE_RATE, encoder).length);
    }

    /// Streaming encode, which never materializes the whole archive.
    @Benchmark
    public void encodeToStream() throws IOException {
        X3BulkEncoder.encodeTo(discard, pcm, FRAMES, 1, SAMPLE_RATE, encoder);
    }

    /// Single-worker baseline for the parallel paths above.
    @Benchmark
    public void encodeSequential(Blackhole blackhole) {
        String prev = System.setProperty("x3a.encode.maxConcurrency", "1");
        try {
            blackhole.consume(X3BulkEncoder.encode(pcm, FRAMES, 1, SAMPLE_RATE, encoder).length);
        } finally {
            if (prev == null) {
                System.clearProperty("x3a.encode.maxConcurrency");
            } else {
                System.setProperty("x3a.encode.maxConcurrency", prev);
            }
        }
    }
}
