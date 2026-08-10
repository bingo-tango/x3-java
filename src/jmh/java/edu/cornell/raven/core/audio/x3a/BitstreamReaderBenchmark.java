package edu.cornell.raven.core.audio.x3a;

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

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 1, time = 1)
@Fork(1)
@State(Scope.Thread)
public class BitstreamReaderBenchmark {

    private Arena arena;
    private MemorySegment payload;

    @Setup(Level.Trial)
    public void setup() {
        arena = Arena.ofConfined();
        payload = arena.allocate(4096);
        payload.fill((byte) 0xA5);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        arena.close();
    }

    @Benchmark
    public void readNibbles(Blackhole blackhole) {
        BitstreamReader reader = new BitstreamReader(payload);
        int acc = 0;
        while (reader.hasRemaining()) {
            try {
                acc += reader.readBits(4);
            } catch (RuntimeException ex) {
                break;
            }
        }
        blackhole.consume(acc);
    }
}
