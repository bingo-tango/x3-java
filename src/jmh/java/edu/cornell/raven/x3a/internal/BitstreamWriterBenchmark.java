package edu.cornell.raven.x3a.internal;

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

/// Measures [BitstreamWriter]'s raw bit-packing throughput, isolated from the codec's
/// Rice/BFP selection logic — the encode-side mirror of [BitstreamReaderBenchmark], and the
/// guard against regressions in the accumulate-and-commit hot path.
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 1, time = 1)
@Fork(1)
@State(Scope.Thread)
public class BitstreamWriterBenchmark {

    /// Code words per invocation, sized so one pass fills a few kilobytes like a real frame.
    private static final int WORDS = 4096;

    private BitstreamWriter writer;
    /// Rice-shaped code widths (small, irregular, byte-boundary-crossing).
    private int[] widths;
    private int[] values;

    @Setup(Level.Trial)
    public void setup() {
        writer = new BitstreamWriter(16 * 1024);
        widths = new int[WORDS];
        values = new int[WORDS];
        // Deterministic spread over the widths a RICE0/1/3 frame actually emits (1..10 bits).
        int state = 0x1234_5678;
        for (int i = 0; i < WORDS; i++) {
            state = state * 1_103_515_245 + 12_345;
            int w = 1 + ((state >>> 8) % 10);
            widths[i] = w;
            values[i] = state & ((1 << w) - 1);
        }
    }

    /// Narrow, irregular writes — the Rice path's shape.
    @Benchmark
    public void writeRiceWidths(Blackhole blackhole) {
        BitstreamWriter bp = writer;
        bp.reset();
        for (int i = 0; i < WORDS; i++) {
            bp.writeBits(values[i], widths[i]);
        }
        bp.wordAlign();
        blackhole.consume(bp.byteLength());
    }

    /// Uniform 16-bit writes — the BFP pass-through path's shape.
    @Benchmark
    public void writeWords16(Blackhole blackhole) {
        BitstreamWriter bp = writer;
        bp.reset();
        for (int i = 0; i < WORDS; i++) {
            bp.writeBits(values[i], 16);
        }
        bp.wordAlign();
        blackhole.consume(bp.byteLength());
    }

    /// Packing plus the frame header's payload checksum, as the encoder actually uses it.
    @Benchmark
    public void writeRiceWidthsWithCrc(Blackhole blackhole) {
        BitstreamWriter bp = writer;
        bp.reset();
        for (int i = 0; i < WORDS; i++) {
            bp.writeBits(values[i], widths[i]);
        }
        bp.wordAlign();
        blackhole.consume(bp.crc());
    }
}
