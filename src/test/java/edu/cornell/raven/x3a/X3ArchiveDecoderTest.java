package edu.cornell.raven.x3a;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies windowed random-access archive decode against [X3Files#decodeArchive(byte[])],
/// which is already cross-validated bit-for-bit against the reference implementations.
class X3ArchiveDecoderTest {

    @TempDir
    Path tmp;

    private static final int SAMPLE_RATE = 48000;

    /// Long enough to span many frames, so windows cross frame boundaries in both directions.
    private static final int FRAMES = 200_000;

    private Path writeArchive(int channels) throws Exception {
        short[] pcm = new short[FRAMES * channels];
        for (int i = 0; i < FRAMES; i++) {
            for (int ch = 0; ch < channels; ch++) {
                pcm[i * channels + ch] = (short) (Math.sin(i * 0.003 + ch) * 11000);
            }
        }
        Path wav = tmp.resolve("in" + channels + ".wav");
        Path x3a = tmp.resolve("in" + channels + ".x3a");
        WavPcm.write(wav, SAMPLE_RATE, channels, pcm);
        X3Files.wavToX3a(wav, x3a);
        return x3a;
    }

    @Test
    void reportsArchiveMetadata() throws Exception {
        Path x3a = writeArchive(1);
        try (X3ArchiveDecoder decoder = new X3ArchiveDecoder(x3a)) {
            assertEquals(SAMPLE_RATE, decoder.sampleRate());
            assertEquals(1, decoder.channels());
            assertEquals(16, decoder.bitDepth());
            assertEquals(FRAMES, decoder.totalSamples());
            assertTrue(decoder.xmlConfig().contains("X3ARCH"));
        }
    }

    @Test
    void windowedDecodeMatchesWholeArchiveDecode() throws Exception {
        Path x3a = writeArchive(1);
        X3Files.DecodedArchive expected = X3Files.decodeArchive(Files.readAllBytes(x3a));

        // Window sizes deliberately unaligned to the encoder's frame length, so reads start and
        // end mid-frame; offsets cover the first frame, an interior seek, and the tail.
        int[] windows = {1, 999, 4096, 50_000};
        long[] offsets = {0, 1, 12_345, FRAMES - 4096};

        try (X3ArchiveDecoder decoder = new X3ArchiveDecoder(x3a)) {
            for (int window : windows) {
                for (long offset : offsets) {
                    short[] got = new short[window];
                    int frames = decoder.decodeSamplesInt(offset, window, got);
                    int want = (int) Math.min(window, FRAMES - offset);
                    assertEquals(want, frames, "frames at offset " + offset + " window " + window);

                    short[] slice = new short[frames];
                    System.arraycopy(expected.pcm(), (int) offset, slice, 0, frames);
                    assertArrayEquals(slice, java.util.Arrays.copyOf(got, frames),
                            "pcm at offset " + offset + " window " + window);
                }
            }
        }
    }

    @Test
    void windowedDecodeMatchesWholeArchiveDecodeStereo() throws Exception {
        Path x3a = writeArchive(2);
        X3Files.DecodedArchive expected = X3Files.decodeArchive(Files.readAllBytes(x3a));

        try (X3ArchiveDecoder decoder = new X3ArchiveDecoder(x3a)) {
            assertEquals(2, decoder.channels());
            int window = 7777;
            long offset = 33_333;
            short[] got = new short[window * 2];
            int frames = decoder.decodeSamplesInt(offset, window, got);
            assertEquals(window, frames);

            short[] slice = new short[window * 2];
            System.arraycopy(expected.pcm(), (int) offset * 2, slice, 0, window * 2);
            assertArrayEquals(slice, got);
        }
    }

    @Test
    void sequentialAndParallelWindowsAgree() throws Exception {
        Path x3a = writeArchive(1);
        int window = 60_000;
        short[] sequential = new short[window];
        short[] parallel = new short[window];

        try (X3ArchiveDecoder decoder = new X3ArchiveDecoder(x3a, DecodeOptions.defaults().withMaxConcurrency(1))) {
            assertEquals(window, decoder.decodeSamplesInt(1000, window, sequential));
        }
        try (X3ArchiveDecoder decoder = new X3ArchiveDecoder(x3a, DecodeOptions.defaults().withMaxConcurrency(4))) {
            assertEquals(window, decoder.decodeSamplesInt(1000, window, parallel));
        }
        assertArrayEquals(sequential, parallel);
    }

    @Test
    void floatDecodeNormalizesIntDecode() throws Exception {
        Path x3a = writeArchive(1);
        int window = 5000;
        short[] ints = new short[window];
        float[] floats = new float[window];

        try (X3ArchiveDecoder decoder = new X3ArchiveDecoder(x3a)) {
            assertEquals(window, decoder.decodeSamplesInt(2048, window, ints));
            assertEquals(window, decoder.decodeSamplesFloat(2048, window, floats));
        }
        for (int i = 0; i < window; i++) {
            assertEquals(ints[i] / 32768.0f, floats[i], 0.0f);
        }
    }

    @Test
    void readsPastEndReturnZeroFrames() throws Exception {
        Path x3a = writeArchive(1);
        try (X3ArchiveDecoder decoder = new X3ArchiveDecoder(x3a)) {
            assertEquals(0, decoder.decodeSamplesInt(FRAMES, 1024, new short[1024]));
            assertEquals(0, decoder.decodeSamplesInt(FRAMES + 5000, 1024, new short[1024]));
        }
    }

    @Test
    void closeIsIdempotent() throws Exception {
        Path x3a = writeArchive(1);
        X3ArchiveDecoder decoder = new X3ArchiveDecoder(x3a);
        decoder.close();
        decoder.close();
    }

    @Test
    void rejectsNonArchiveAsFormatError() throws Exception {
        Path junk = tmp.resolve("junk.x3a");
        Files.write(junk, "this is not an X3 archive, not even close".getBytes());

        assertFalse(X3Readers.isArchive(junk));
        assertThrows(X3FormatException.class, () -> new X3ArchiveDecoder(junk));
    }

    @Test
    void readersOpenSelectsArchiveDecoderByMagic() throws Exception {
        Path x3a = writeArchive(1);
        assertTrue(X3Readers.isArchive(x3a));
        try (X3SampleReader reader = X3Readers.open(x3a)) {
            assertInstanceOf(X3ArchiveDecoder.class, reader);
            assertEquals(FRAMES, reader.totalSamples());
            assertEquals(SAMPLE_RATE, reader.sampleRate());
        }
    }

    @Test
    void readHeaderAgreesWithArchiveDecoder() throws Exception {
        Path x3a = writeArchive(2);
        X3Files.X3Header header = X3Files.readHeader(x3a);
        try (X3ArchiveDecoder decoder = new X3ArchiveDecoder(x3a)) {
            assertEquals(decoder.sampleRate(), header.sampleRate());
            assertEquals(decoder.channels(), header.channels());
            assertEquals(decoder.bitDepth(), header.bitDepth());
            assertEquals(decoder.totalSamples(), header.frames());
        }
    }
}
