package edu.cornell.raven.core.audio.x3a;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Round-trip conversion benchmark for WAV files under {@code ./test}.
 * Prints CSV-style metrics comparable to the x3-rust / paper suite:
 * <pre>
 * File,Algorithm,File Size (B),Time,Max Mem Usage (kB),Compressed Size (B)
 * ...
 * Algorithm,Compression ratio,Compression speed (MB/s),Decompression speed (MB/s)
 * </pre>
 */
class ConversionBenchmarkTest {

    private static final Path TEST_DIR = Path.of("test");
    private static final MemoryMXBean HEAP = ManagementFactory.getMemoryMXBean();

    private static final class Row {
        final String file;
        final String algorithm;
        final long fileSizeB;
        final double timeSec;
        final long maxMemKb;
        final long outSizeB;

        Row(String file, String algorithm, long fileSizeB, double timeSec, long maxMemKb, long outSizeB) {
            this.file = file;
            this.algorithm = algorithm;
            this.fileSizeB = fileSizeB;
            this.timeSec = timeSec;
            this.maxMemKb = maxMemKb;
            this.outSizeB = outSizeB;
        }

        String csv() {
            return file + "," + algorithm + "," + fileSizeB + ","
                    + formatTime(timeSec) + "," + maxMemKb + "," + outSizeB;
        }
    }

    private static final class Timed {
        final double timeSec;
        final long maxMemKb;
        final long outSizeB;

        Timed(double timeSec, long maxMemKb, long outSizeB) {
            this.timeSec = timeSec;
            this.maxMemKb = maxMemKb;
            this.outSizeB = outSizeB;
        }
    }

    @Test
    void convertTestWavs_x3aRoundTrip_andPrintMetrics() throws Exception {
        List<Path> wavs = listWavs(TEST_DIR);
        assumeTrue(!wavs.isEmpty(), "No .wav files in ./test — place benchmark WAVs there and re-run");

        List<Row> rows = new ArrayList<>();
        long totalWavBytes = 0;
        long totalX3aBytes = 0;
        double totalX3aEncSec = 0;
        double totalX3aDecSec = 0;

        long totalFlacBytes = 0;
        double totalFlacEncSec = 0;
        double totalFlacDecSec = 0;
        boolean flacOk = isFlacAvailable();

        // Per-file paths and sizes prepared once so encode and decode can run in separate passes.
        record FileJob(Path wav, Path x3a, Path wavFromX3a, Path flac, Path wavFromFlac,
                       String base, long wavSize) {}
        List<FileJob> jobs = new ArrayList<>(wavs.size());
        for (Path wav : wavs) {
            String base = stripExtension(wav.getFileName().toString());
            long wavSize = Files.size(wav);
            totalWavBytes += wavSize;
            jobs.add(new FileJob(
                    wav,
                    TEST_DIR.resolve(base + ".x3a"),
                    TEST_DIR.resolve(base + "_from_x3a.wav"),
                    TEST_DIR.resolve(base + ".flac"),
                    TEST_DIR.resolve(base + "_from_flac.wav"),
                    base,
                    wavSize));
        }

        System.out.println("File,Algorithm,File Size (B),Time,Max Mem Usage (kB),Compressed Size (B)");

        // Pass 1: all wav -> x3a
        long[] x3aSizes = new long[jobs.size()];
        for (int i = 0; i < jobs.size(); i++) {
            FileJob job = jobs.get(i);
            Timed enc = timeOp(() -> X3Files.wav_to_x3a(job.wav, job.x3a), () -> Files.size(job.x3a));
            Row encRow = new Row(job.wav.getFileName().toString(), "wav_to_x3a",
                    job.wavSize, enc.timeSec, enc.maxMemKb, enc.outSizeB);
            rows.add(encRow);
            System.out.println(encRow.csv());
            x3aSizes[i] = enc.outSizeB;
            totalX3aBytes += enc.outSizeB;
            totalX3aEncSec += enc.timeSec;
        }

        // Pass 2: all x3a -> wav (+ lossless check)
        for (int i = 0; i < jobs.size(); i++) {
            FileJob job = jobs.get(i);
            long x3aSize = x3aSizes[i];
            Timed dec = timeOp(() -> X3Files.x3a_to_wav(job.x3a, job.wavFromX3a),
                    () -> Files.size(job.wavFromX3a));
            Row decRow = new Row(job.x3a.getFileName().toString(), "x3a_to_wav",
                    x3aSize, dec.timeSec, dec.maxMemKb, dec.outSizeB);
            rows.add(decRow);
            System.out.println(decRow.csv());
            totalX3aDecSec += dec.timeSec;

            WavPcm.WavData orig = WavPcm.read(job.wav);
            WavPcm.WavData back = WavPcm.read(job.wavFromX3a);
            assertEquals(orig.sampleRate, back.sampleRate, job.base + " sampleRate");
            assertEquals(orig.channels, back.channels, job.base + " channels");
            assertArrayEquals(orig.samples, back.samples, job.base + " PCM round-trip");
        }

        if (flacOk) {
            // Pass 3: all wav -> flac
            long[] flacSizes = new long[jobs.size()];
            for (int i = 0; i < jobs.size(); i++) {
                FileJob job = jobs.get(i);
                Files.deleteIfExists(job.flac);
                Timed fEnc = timeOp(() -> runFlacEncode(job.wav, job.flac), () -> Files.size(job.flac));
                Row fEncRow = new Row(job.wav.getFileName().toString(), "wav_to_flac",
                        job.wavSize, fEnc.timeSec, fEnc.maxMemKb, fEnc.outSizeB);
                rows.add(fEncRow);
                System.out.println(fEncRow.csv());
                flacSizes[i] = fEnc.outSizeB;
                totalFlacBytes += fEnc.outSizeB;
                totalFlacEncSec += fEnc.timeSec;
            }

            // Pass 4: all flac -> wav
            for (int i = 0; i < jobs.size(); i++) {
                FileJob job = jobs.get(i);
                Files.deleteIfExists(job.wavFromFlac);
                long flacSize = flacSizes[i];
                Timed fDec = timeOp(() -> runFlacDecode(job.flac, job.wavFromFlac),
                        () -> Files.size(job.wavFromFlac));
                Row fDecRow = new Row(job.flac.getFileName().toString(), "flac_to_wav",
                        flacSize, fDec.timeSec, fDec.maxMemKb, fDec.outSizeB);
                rows.add(fDecRow);
                System.out.println(fDecRow.csv());
                totalFlacDecSec += fDec.timeSec;
                assertTrue(Files.size(job.wavFromFlac) > 44, job.base + " flac decode produced WAV");
            }
        }

        System.out.println();
        System.out.println("Algorithm,Compression ratio,Compression speed (MB/s),Decompression speed (MB/s)");
        System.out.println(summaryLine("x3a", totalWavBytes, totalX3aBytes, totalX3aEncSec, totalX3aDecSec));
        if (flacOk && totalFlacEncSec > 0) {
            System.out.println(summaryLine("flac", totalWavBytes, totalFlacBytes, totalFlacEncSec, totalFlacDecSec));
        } else {
            System.out.println("flac,skipped (flac CLI not on PATH),,");
        }

        assertTrue(totalX3aBytes > 0);
        assertTrue(totalX3aEncSec >= 0);
        assertEquals(wavs.size() * 2L, rows.stream().filter(r -> r.algorithm.contains("x3a")).count());
    }

    private static String summaryLine(String algo, long wavBytes, long compressedBytes,
                                      double encSec, double decSec) {
        double ratio = compressedBytes / (double) wavBytes;
        double encMBs = mbPerSec(wavBytes, encSec);
        double decMBs = mbPerSec(wavBytes, decSec);
        return algo + "," + formatRatio(ratio) + "," + formatRatio(encMBs) + "," + formatRatio(decMBs);
    }

    private static double mbPerSec(long bytes, double sec) {
        if (sec <= 0) {
            return 0;
        }
        return (bytes / 1_000_000.0) / sec;
    }

    private static String formatTime(double sec) {
        // Match sample style: variable precision, no forced trailing zeros
        String s = String.format(Locale.US, "%.6f", sec);
        // trim trailing zeros after decimal but keep at least one digit
        if (s.indexOf('.') >= 0) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", ".0");
        }
        return s;
    }

    private static String formatRatio(double v) {
        return String.format(Locale.US, "%.20f", v).replaceAll("0+$", "").replaceAll("\\.$", ".0");
    }

    private static List<Path> listWavs(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.wav")) {
            for (Path p : ds) {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                // Skip round-trip outputs from previous runs
                if (name.endsWith("_from_x3a.wav") || name.endsWith("_from_flac.wav")) {
                    continue;
                }
                out.add(p);
            }
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface IoLong {
        long get() throws Exception;
    }

    private static Timed timeOp(IoAction action, IoLong outSize) throws Exception {
        // Encourage a quieter baseline; do not force full GC mid-suite.
        long baseline = HEAP.getHeapMemoryUsage().getUsed();
        AtomicLong peak = new AtomicLong(baseline);

        Thread sampler = Thread.ofVirtual().name("mem-sample").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                long u = HEAP.getHeapMemoryUsage().getUsed();
                peak.updateAndGet(prev -> Math.max(prev, u));
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });

        long t0 = System.nanoTime();
        try {
            action.run();
        } finally {
            sampler.interrupt();
            sampler.join(500);
        }
        long t1 = System.nanoTime();
        peak.updateAndGet(prev -> Math.max(prev, HEAP.getHeapMemoryUsage().getUsed()));
        double sec = (t1 - t0) / 1_000_000_000.0;
        long memKb = Math.max(0L, peak.get() / 1024L);
        return new Timed(sec, memKb, outSize.get());
    }

    private static boolean isFlacAvailable() {
        try {
            Process p = new ProcessBuilder("flac", "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void runFlacEncode(Path wav, Path flac) throws Exception {
        Process p = new ProcessBuilder(
                "flac",
                "-f",
                "-s",
                "-o", flac.toAbsolutePath().toString(),
                wav.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        String log = readProcess(p, 600);
        if (p.exitValue() != 0) {
            throw new IOException("flac encode failed: " + log);
        }
    }

    private static void runFlacDecode(Path flac, Path wav) throws Exception {
        Process p = new ProcessBuilder(
                "flac",
                "-d",
                "-f",
                "-s",
                "-o", wav.toAbsolutePath().toString(),
                flac.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        String log = readProcess(p, 600);
        if (p.exitValue() != 0) {
            throw new IOException("flac decode failed: " + log);
        }
    }

    private static String readProcess(Process p, int timeoutSec) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            while (br.ready()) {
                sb.append(br.readLine()).append('\n');
            }
            if (!done) {
                p.destroyForcibly();
                throw new IOException("process timed out: " + sb);
            }
        }
        return sb.toString();
    }
}
