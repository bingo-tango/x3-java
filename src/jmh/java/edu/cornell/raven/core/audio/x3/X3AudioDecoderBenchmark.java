package edu.cornell.raven.core.audio.x3;

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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 1, time = 1)
@Fork(1)
@State(Scope.Thread)
public class X3AudioDecoderBenchmark {

    private Arena arena;
    private MemorySegment payload;
    private X3AudioDecoder decoder;
    private short[] intDest;
    private float[] floatDest;
    private short[] scratch;

    @Setup(Level.Trial)
    public void setup() {
        arena = Arena.ofConfined();
        payload = arena.allocate(8192);
        payload.fill((byte) 0x3C);
        decoder = new X3AudioDecoder();
        intDest = new short[4096];
        floatDest = new float[4096];
        scratch = new short[4096];
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        arena.close();
    }

    @Benchmark
    public void decodeChunkInt(Blackhole blackhole) {
        blackhole.consume(decoder.decodeChunkInt(payload, 1, intDest, 0));
    }

    @Benchmark
    public void decodeChunkFloat(Blackhole blackhole) {
        blackhole.consume(decoder.decodeChunkFloat(payload, 1, floatDest, 0, scratch));
    }
}
