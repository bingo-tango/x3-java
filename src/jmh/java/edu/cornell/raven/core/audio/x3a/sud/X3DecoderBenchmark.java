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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 1, time = 1)
@Fork(1)
@State(Scope.Thread)
public class X3DecoderBenchmark {

    private Path sudFile;
    private X3Decoder decoder;
    private short[] intDest;
    private float[] floatDest;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        sudFile = Files.createTempFile("x3a-bench-", ".sud");
        Files.write(sudFile, new byte[4096]);
        decoder = new X3Decoder(sudFile);
        intDest = new short[2048];
        floatDest = new float[2048];
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        if (decoder != null) {
            decoder.close();
        }
        Files.deleteIfExists(sudFile);
    }

    @Benchmark
    public void decodeSamplesInt(Blackhole blackhole) {
        blackhole.consume(decoder.decodeSamplesInt(0L, intDest.length, intDest));
    }

    @Benchmark
    public void decodeSamplesFloat(Blackhole blackhole) {
        blackhole.consume(decoder.decodeSamplesFloat(0L, floatDest.length, floatDest));
    }
}
