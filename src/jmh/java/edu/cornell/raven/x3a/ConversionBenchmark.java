package edu.cornell.raven.x3a;

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

/// Paper-suite file conversion benchmark (`./test/*.wav` ↔ `.x3a`).
///
/// Uses [Mode#SingleShotTime] because each op is a full file conversion (I/O + codec),
/// not a micro-throughput loop. Warmup/measurement are kept minimal so the suite
/// finishes quickly.
///
/// Run (CSV summary + JMH scores):
/// ```
///   ./gradlew conversionBenchmark
/// ```
/// Or via the generic JMH task:
/// ```
///   ./gradlew jmh -Pjmh.includes=.*ConversionBenchmark.*
/// ```
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 1)
@Fork(1)
public class ConversionBenchmark {

    private static final Path TEST_DIR = Path.of("test");

    /// Input WAV size (bytes) by base name, filled during encode trials.
    static final ConcurrentHashMap<String, Long> WAV_BYTES = new ConcurrentHashMap<>();
    /// Compressed .x3a size (bytes) by base name.
    static final ConcurrentHashMap<String, Long> X3A_BYTES = new ConcurrentHashMap<>();

    @State(Scope.Thread)
    public static class EncodeInput {
        @Param({"GI16", "GI60", "GR48", "LI192", "NO96", "PI240"})
        public String file;

        Path wav;
        Path x3a;
        Path flacOut;
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
            flacOut = TEST_DIR.resolve(file + ".jmh.flac");
            wavBytes = Files.size(wav);
            WAV_BYTES.put(file, wavBytes);
        }

        @TearDown(Level.Trial)
        public void tearDown() throws IOException {
            // Record compressed sizes after the trial so the benchmark body stays clean.
            if (Files.exists(x3a))    X3A_BYTES.put(file,          Files.size(x3a));
            if (Files.exists(flacOut)) X3A_BYTES.put(file + ".flac", Files.size(flacOut));
            Files.deleteIfExists(x3a);
            Files.deleteIfExists(flacOut);
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
            X3Files.wavToX3a(wav, x3a);
            x3aBytes = Files.size(x3a);
            X3A_BYTES.put(file, x3aBytes);
        }

        @TearDown(Level.Trial)
        public void tearDown() throws IOException {
            Files.deleteIfExists(x3a);
            Files.deleteIfExists(wavOut);
        }
    }

    /// Input state for the FLAC decode benchmark. Encodes WAV -> FLAC once (untimed)
    /// so that `flac_to_wav` measures only the decode pass.
    @State(Scope.Thread)
    public static class FlacDecodeInput {
        @Param({"GI16", "GI60", "GR48", "LI192", "NO96", "PI240"})
        public String file;

        Path flac;
        Path wavOut;
        long flacBytes;

        @Setup(Level.Trial)
        public void setup() throws Exception {
            Path wav = TEST_DIR.resolve(file + ".wav");
            if (!Files.isRegularFile(wav)) {
                throw new IllegalStateException(
                        "Missing benchmark WAV: " + wav.toAbsolutePath()
                                + " — place the paper suite under ./test");
            }
            WAV_BYTES.putIfAbsent(file, Files.size(wav));
            flac = TEST_DIR.resolve(file + ".jmh_pre.flac");
            wavOut = TEST_DIR.resolve(file + ".jmh_from_flac.wav");
            // Untimed encode so the benchmark measures flac_to_wav only.
            ProcessBuilder pb = new ProcessBuilder("flac", wav.toString(), "-o", flac.toString(), "-f", "--silent");
            Process p = pb.start();
            int exitCode;
            try {
                exitCode = p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while encoding FLAC fixture", e);
            }
            if (exitCode != 0) {
                String err = new String(p.getErrorStream().readAllBytes());
                throw new IOException("FLAC fixture encode failed (exit " + exitCode + "): " + err);
            }
            flacBytes = Files.size(flac);
            X3A_BYTES.put(file + ".flac", flacBytes);
        }

        @TearDown(Level.Trial)
        public void tearDown() throws IOException {
            Files.deleteIfExists(flac);
            Files.deleteIfExists(wavOut);
        }
    }

    /// Encode side of the round trip (WAV -> X3A).
    @Benchmark
    public void wav_to_x3a(EncodeInput in) throws Exception {
        X3Files.wavToX3a(in.wav, in.x3a);
    }

    /// Encode side of the round trip (WAV -> FLAC). Requires `flac` CLI on PATH.
    @Benchmark
    public void wav_to_flac(EncodeInput in) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "flac", in.wav.toString(), "-o", in.flacOut.toString(), "-f", "--silent");
        int exitCode = pb.start().waitFor();
        if (exitCode != 0) {
            throw new IOException("flac encode failed (exit " + exitCode + ") for " + in.file);
        }
    }

/// Decode side of the round trip (X3A -> WAV).
    @Benchmark
    public void x3a_to_wav(DecodeInput in) throws Exception {
        X3Files.x3aToWav(in.x3a, in.wavOut);
    }

    /// Decode side of the round trip (FLAC -> WAV). Requires `flac` CLI on PATH.
    @Benchmark
    public void flac_to_wav(FlacDecodeInput in) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "flac", "--decode", in.flac.toString(), "-o", in.wavOut.toString(), "-f", "--silent");
        int exitCode = pb.start().waitFor();
        if (exitCode != 0) {
            throw new IOException("flac decode failed (exit " + exitCode + ") for " + in.file);
        }
    }

    /// Standalone entry: runs this class with minimal single-shot settings and prints
    /// CSV metrics comparable to x3-rust `test/bench.sh`.
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
        // file -> (wav_to_x3a, wav_to_flac, x3a_to_wav, flac_to_wav)
        Map<String, double[]> times = new LinkedHashMap<>();
        for (RunResult rr : results) {
            String bench = rr.getParams().getBenchmark();
            String file = rr.getParams().getParam("file");
            if (file == null) {
                continue;
            }
            double sec = rr.getPrimaryResult().getScore();
            // Initialize time array with 4 slots: x3a(0), flac(1), x3a_dec(2), flac_dec(3)
            double[] t = times.computeIfAbsent(file, k -> new double[] {Double.NaN, Double.NaN, Double.NaN, Double.NaN});
            if (bench.endsWith(".wav_to_x3a")) {
                t[0] = sec;
            } else if (bench.endsWith(".wav_to_flac")) {
                t[1] = sec;
            } else if (bench.endsWith(".x3a_to_wav")) {
                t[2] = sec;
            } else if (bench.endsWith(".flac_to_wav")) {
                t[3] = sec;
            }
        }

        List<String> files = new ArrayList<>(times.keySet());
        files.sort(Comparator.naturalOrder());

        System.out.println();
        // New CSV header to reflect the new benchmarks: WAV->X3A, WAV->FLAC, X3A->WAV, FLAC->WAV
        System.out.println("File,Algorithm,File Size (B),Time,Max Mem Usage (kB),Compressed Size (B)");

        long totalWav = 0;
        long totalX3a = 0;
        double totalEnc_x3a = 0;
        double totalEnc_flac = 0;
        double totalDec_x3a = 0;
        double totalDec_flac = 0;
        long totalFlac = 0;      // sum of compressed FLAC sizes (for ratio)
        long totalWavFlac = 0;   // sum of WAV sizes for files where FLAC ran (may differ from totalWav if not all files ran)

        for (String file : files) {
            long wavBytes = sizeOrFile(file, WAV_BYTES);
            long x3aBytes = X3A_BYTES.getOrDefault(file, 0L);
            long flacBytes = X3A_BYTES.getOrDefault(file + ".flac", 0L);

            // WAV -> X3A (Index 0)
            double enc_x3a = times.get(file)[0];
            if (!Double.isNaN(enc_x3a)) {
                System.out.println(file + ".wav,wav_to_x3a," + wavBytes + ","
                        + formatTime(enc_x3a) + ",0," + x3aBytes);
                totalWav += wavBytes;
                totalX3a += x3aBytes;
                totalEnc_x3a += enc_x3a;
            }

            // WAV -> FLAC (Index 1)
            double enc_flac = times.get(file)[1];
            if (!Double.isNaN(enc_flac)) {
                System.out.println(file + ".wav,wav_to_flac," + wavBytes + ","
                        + formatTime(enc_flac) + ",0," + flacBytes);
                totalEnc_flac += enc_flac;
                totalFlac += flacBytes;
                totalWavFlac += wavBytes;
            }

            // X3A -> WAV (Index 2)
            double dec_x3a = times.get(file)[2];
            if (!Double.isNaN(dec_x3a)) {
                System.out.println(file + ".x3a,x3a_to_wav," + x3aBytes + ","
                        + formatTime(dec_x3a) + ",0," + wavBytes);
                totalDec_x3a += dec_x3a;
            }

            // FLAC -> WAV (Index 3)
            double dec_flac = times.get(file)[3];
            if (!Double.isNaN(dec_flac)) {
                System.out.println(file + ".flac,flac_to_wav," + flacBytes + ","
                        + formatTime(dec_flac) + ",0," + wavBytes);
                totalDec_flac += dec_flac;
            }
        }

        System.out.println();
        System.out.println("Algorithm,Compression ratio,Compression speed (MB/s),Decompression speed (MB/s)");

        // Summary for x3a conversion
        if (totalWav > 0 && totalEnc_x3a > 0) {
            double ratio = totalX3a / (double) totalWav;
            double encMibs_x3a = mibPerSec(totalWav, totalEnc_x3a);
            double decMibs_x3a = mibPerSec(totalWav, totalDec_x3a);
            System.out.println("x3a," + formatRatio(ratio) + "," + formatRatio(encMibs_x3a) + ","
                    + formatRatio(decMibs_x3a));
        } else {
            System.out.println("x3a,n/a,,");
        }

        // Summary for FLAC conversion (Compression speed = WAV->FLAC, Decompression speed = FLAC->WAV)
        if (totalWavFlac > 0 && totalEnc_flac > 0 && totalDec_flac > 0) {
            double ratio_flac = totalFlac / (double) totalWavFlac;
            double encMibs_flac = mibPerSec(totalWavFlac, totalEnc_flac);
            double decMibs_flac = mibPerSec(totalWavFlac, totalDec_flac);
            System.out.println("flac," + formatRatio(ratio_flac) + ","
                    + formatRatio(encMibs_flac) + "," + formatRatio(decMibs_flac));
        } else {
            System.out.println("flac,n/a,,");
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

    /// Match x3-rust bench.sh: bytes / time / 1024 / 1024 (MiB/s).
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
