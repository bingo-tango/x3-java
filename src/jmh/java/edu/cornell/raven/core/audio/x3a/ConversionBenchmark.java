package edu.cornell.raven.core.audio.x3a;

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
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Paper-suite file conversion benchmark ({@code ./test/*.wav} ↔ {@code .x3a}).
 * <p>
 * Uses {@link Mode#SingleShotTime} because each op is a full file conversion (I/O + codec),
 * not a micro-throughput loop. Warmup/measurement are kept minimal so the suite finishes quickly.
 * <p>
 * Run (CSV summary + JMH scores):
 * <pre>
 *   ./gradlew conversionBenchmark
 * </pre>
 * Or via the generic JMH task:
 * <pre>
 *   ./gradlew jmh -Pjmh.includes=.*ConversionBenchmark.*
 * </pre>
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 1)
@Fork(1)
public class ConversionBenchmark {

    private static final Path TEST_DIR = Path.of("test");

    /** Input WAV size (bytes) by base name, filled during encode trials. */
    static final ConcurrentHashMap<String, Long> WAV_BYTES = new ConcurrentHashMap<>();
    /** Compressed .x3a size (bytes) by base name. */
    static final ConcurrentHashMap<String, Long> X3A_BYTES = new ConcurrentHashMap<>();

    @State(Scope.Thread)
    public static class EncodeInput {
        @Param({"GI16", "GI60", "GR48", "LI192", "NO96", "PI240"})
        public String file;

        Path wav;
        Path x3a;
        long wavBytes;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            wav = TEST_DIR.resolve(file + ".wav");
            if (!Files.isRegularFile(wav)) {
                throw new IllegalStateException(
                        "Missing benchmark WAV: " + wav.toAbsolutePath()
                                + " — place the paper suite under ./test");
            }
            x3a = TEST_DIR.resolve(file + ".jmh.x3a");
            wavBytes = Files.size(wav);
            WAV_BYTES.put(file, wavBytes);
        }

        @TearDown(Level.Trial)
        public void tearDown() throws IOException {
            Files.deleteIfExists(x3a);
        }
    }

    @State(Scope.Thread)
    public static class DecodeInput {
        @Param({"GI16", "GI60", "GR48", "LI192", "NO96", "PI240"})
        public String file;

        Path x3a;
        Path wavOut;
        long x3aBytes;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            Path wav = TEST_DIR.resolve(file + ".wav");
            if (!Files.isRegularFile(wav)) {
                throw new IllegalStateException(
                        "Missing benchmark WAV: " + wav.toAbsolutePath()
                                + " — place the paper suite under ./test");
            }
            WAV_BYTES.putIfAbsent(file, Files.size(wav));
            x3a = TEST_DIR.resolve(file + ".jmh_pre.x3a");
            wavOut = TEST_DIR.resolve(file + ".jmh_from_x3a.wav");
            // Untimed prepare so the benchmark measures x3a_to_wav only.
            X3Files.wav_to_x3a(wav, x3a);
            x3aBytes = Files.size(x3a);
            X3A_BYTES.put(file, x3aBytes);
        }

        @TearDown(Level.Trial)
        public void tearDown() throws IOException {
            Files.deleteIfExists(x3a);
            Files.deleteIfExists(wavOut);
        }
    }

    @Benchmark
    public long wav_to_x3a(EncodeInput in) throws Exception {
        X3Files.wav_to_x3a(in.wav, in.x3a);
        long out = Files.size(in.x3a);
        X3A_BYTES.put(in.file, out);
        return out;
    }

    @Benchmark
    public long x3a_to_wav(DecodeInput in) throws Exception {
        X3Files.x3a_to_wav(in.x3a, in.wavOut);
        return Files.size(in.wavOut);
    }

    /**
     * Standalone entry: runs this class with minimal single-shot settings and prints
     * CSV metrics comparable to x3-rust {@code test/bench.sh}.
     */
    public static void main(String[] args) throws Exception {
        WAV_BYTES.clear();
        X3A_BYTES.clear();

        int warmup = 1;
        int iters = 1;
        for (int i = 0; i < args.length; i++) {
            if ("-w".equals(args[i]) && i + 1 < args.length) {
                warmup = Integer.parseInt(args[++i]);
            } else if ("-i".equals(args[i]) && i + 1 < args.length) {
                iters = Integer.parseInt(args[++i]);
            }
        }

        // forks(0): same JVM so size maps filled by @Setup/@Benchmark are visible to printCsv,
        // and we avoid an extra process spawn for this already long file-level suite.
        Options opt = new OptionsBuilder()
                .include(ConversionBenchmark.class.getSimpleName())
                .mode(Mode.SingleShotTime)
                .timeUnit(TimeUnit.SECONDS)
                .warmupIterations(warmup)
                .measurementIterations(iters)
                .forks(0)
                .shouldFailOnError(true)
                .build();

        Collection<RunResult> results = new Runner(opt).run();
        printCsv(results);
    }

    private static void printCsv(Collection<RunResult> results) throws IOException {
        // file -> (encSec, decSec)
        Map<String, double[]> times = new LinkedHashMap<>();
        for (RunResult rr : results) {
            String bench = rr.getParams().getBenchmark();
            String file = rr.getParams().getParam("file");
            if (file == null) {
                continue;
            }
            double sec = rr.getPrimaryResult().getScore();
            double[] t = times.computeIfAbsent(file, k -> new double[] {Double.NaN, Double.NaN});
            if (bench.endsWith(".wav_to_x3a")) {
                t[0] = sec;
            } else if (bench.endsWith(".x3a_to_wav")) {
                t[1] = sec;
            }
        }

        List<String> files = new ArrayList<>(times.keySet());
        files.sort(Comparator.naturalOrder());

        System.out.println();
        System.out.println("File,Algorithm,File Size (B),Time,Max Mem Usage (kB),Compressed Size (B)");

        long totalWav = 0;
        long totalX3a = 0;
        double totalEnc = 0;
        double totalDec = 0;

        for (String file : files) {
            long wavBytes = sizeOrFile(file, WAV_BYTES);
            long x3aBytes = X3A_BYTES.getOrDefault(file, 0L);
            double enc = times.get(file)[0];
            if (!Double.isNaN(enc)) {
                System.out.println(file + ".wav,wav_to_x3a," + wavBytes + ","
                        + formatTime(enc) + ",0," + x3aBytes);
                totalWav += wavBytes;
                totalX3a += x3aBytes;
                totalEnc += enc;
            }
        }
        for (String file : files) {
            long wavBytes = sizeOrFile(file, WAV_BYTES);
            long x3aBytes = X3A_BYTES.getOrDefault(file, 0L);
            double dec = times.get(file)[1];
            if (!Double.isNaN(dec)) {
                System.out.println(file + ".x3a,x3a_to_wav," + x3aBytes + ","
                        + formatTime(dec) + ",0," + wavBytes);
                totalDec += dec;
            }
        }

        System.out.println();
        System.out.println("Algorithm,Compression ratio,Compression speed (MB/s),Decompression speed (MB/s)");
        if (totalWav > 0 && totalEnc > 0 && totalDec > 0) {
            double ratio = totalX3a / (double) totalWav;
            double encMibs = mibPerSec(totalWav, totalEnc);
            double decMibs = mibPerSec(totalWav, totalDec);
            System.out.println("x3a," + formatRatio(ratio) + "," + formatRatio(encMibs) + ","
                    + formatRatio(decMibs));
        } else {
            System.out.println("x3a,n/a,,");
        }
    }

    private static long sizeOrFile(String base, ConcurrentHashMap<String, Long> map) throws IOException {
        Long cached = map.get(base);
        if (cached != null) {
            return cached;
        }
        Path wav = TEST_DIR.resolve(base + ".wav");
        return Files.isRegularFile(wav) ? Files.size(wav) : 0L;
    }

    /** Match x3-rust bench.sh: bytes / time / 1024 / 1024 (MiB/s). */
    private static double mibPerSec(long bytes, double sec) {
        if (sec <= 0) {
            return 0;
        }
        return (bytes / (1024.0 * 1024.0)) / sec;
    }

    private static String formatTime(double sec) {
        String s = String.format(Locale.US, "%.6f", sec);
        if (s.indexOf('.') >= 0) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", ".0");
        }
        return s;
    }

    private static String formatRatio(double v) {
        return String.format(Locale.US, "%.20f", v).replaceAll("0+$", "").replaceAll("\\.$", ".0");
    }
}
