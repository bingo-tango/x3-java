package edu.cornell.raven.core.audio.x3a.internal;

import edu.cornell.raven.core.audio.x3a.X3AudioDecoder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;

/// Measures [ChunkPipeline]'s windowed-decode throughput across concurrency levels,
/// to check that raising `maxConcurrency` actually pays off rather than adding overhead.
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 1, time = 1)
@Fork(1)
@State(Scope.Thread)
public class ChunkPipelineBenchmark {

    /// Concurrency levels swept by this benchmark.
    @Param({"1", "2", "4", "8"})
    public int concurrency;

    private Arena arena;
    private ChunkPipeline pipeline;
    private short[] intDest;
    private float[] floatDest;
    private short[] scratch;

    @Setup(Level.Trial)
    public void setup() {
        arena = Arena.ofConfined();
        MemorySegment mapped = arena.allocate(64 * 1024);
        mapped.fill((byte) 0);
        // Empty index table stub; real tables come from the SUD container layer.
        pipeline = new ChunkPipeline(mapped, new long[0], 0, new X3AudioDecoder(), 1, concurrency);
        intDest = new short[8192];
        floatDest = new float[8192];
        scratch = new short[8192];
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        arena.close();
    }

    /// Int window read at [#concurrency].
    @Benchmark
    public void decodeWindowInt(Blackhole blackhole) {
        blackhole.consume(pipeline.decodeWindowInt(0L, intDest.length, intDest));
    }

    /// Float window read at [#concurrency], including normalization.
    @Benchmark
    public void decodeWindowFloat(Blackhole blackhole) {
        blackhole.consume(pipeline.decodeWindowFloat(0L, floatDest.length, floatDest, scratch));
    }
}
