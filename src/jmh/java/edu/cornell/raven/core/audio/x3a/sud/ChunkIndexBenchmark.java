package edu.cornell.raven.core.audio.x3a.sud;

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

    private static final ValueLayout.OfShort LE_SHORT =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final int SAMPLE_RATE = 48_000;

    @Setup(Level.Trial)
    public void setup() {
        arena = Arena.ofConfined();
        // Synthetic stream: N audio chunks, each a real 20-byte record header + 32-byte payload
        int chunks = 256;
        long size = chunks * (RecordHeader.BYTES + 32L);
        mappedFile = arena.allocate(size);
        mappedFile.fill((byte) 0);

        long offset = 0L;
        for (int i = 0; i < chunks; i++) {
            mappedFile.set(ValueLayout.JAVA_BYTE, offset, (byte) 0x52);
            mappedFile.set(ValueLayout.JAVA_BYTE, offset + 1, (byte) 0xA9);
            mappedFile.set(LE_SHORT, offset + 4, (short) 32); // payloadLength
            mappedFile.set(LE_SHORT, offset + 6, (short) 16); // decoded sample count for this chunk
            offset += RecordHeader.BYTES + 32L;
        }
        index = new ChunkIndex();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        arena.close();
    }

    @Benchmark
    public void buildIndex(Blackhole blackhole) {
        index.build(mappedFile, SAMPLE_RATE);
        blackhole.consume(index.chunkCount());
    }
}
