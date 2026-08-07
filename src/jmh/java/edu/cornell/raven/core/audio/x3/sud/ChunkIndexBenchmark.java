package edu.cornell.raven.core.audio.x3.sud;

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
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 1, time = 1)
@Fork(1)
@State(Scope.Thread)
public class ChunkIndexBenchmark {

    private Arena arena;
    private MemorySegment mappedFile;
    private ChunkIndex index;

    @Setup(Level.Trial)
    public void setup() {
        arena = Arena.ofConfined();
        // Synthetic stream: header + N acoustic chunks
        int chunks = 256;
        long size = 128L + chunks * (13L + 32L);
        mappedFile = arena.allocate(size);
        mappedFile.fill((byte) 0);

        long offset = 128L;
        for (int i = 0; i < chunks; i++) {
            mappedFile.set(ValueLayout.JAVA_BYTE, offset, ChunkType.ACOUSTIC_AUDIO.id());
            mappedFile.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), offset + 1, 32);
            mappedFile.set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), offset + 5, i);
            offset += 13L + 32L;
        }
        index = new ChunkIndex();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        arena.close();
    }

    @Benchmark
    public void buildIndex(Blackhole blackhole) {
        index.build(mappedFile);
        blackhole.consume(index.chunkCount());
    }
}
