package edu.cornell.raven.core.audio.x3a.sud;

import edu.cornell.raven.core.audio.x3a.DecodeOptions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class X3DecoderTest {

    @TempDir
    Path tempDir;

    @Test
    void openDecodeClose_emptyFileDefaults() throws Exception {
        Path sud = tempDir.resolve("tiny.sud");
        Files.write(sud, new byte[256]);

        try (X3Decoder decoder = new X3Decoder(sud)) {
            short[] ints = new short[32];
            float[] floats = new float[32];

            // No audio chunks → nothing to decode
            assertEquals(0, decoder.decodeSamplesInt(0L, 32, ints));
            assertEquals(0, decoder.decodeSamplesFloat(0L, 32, floats));
        }
    }

    @Test
    void metadata_exposesPhase1Config() throws Exception {
        Path sud = tempDir.resolve("tiny.sud");
        Files.write(sud, new byte[256]);

        try (X3Decoder decoder = new X3Decoder(sud)) {
            FileMetadata metadata = decoder.metadata();

            assertEquals(576_000, metadata.sampleRate());
            assertEquals(1, metadata.channels());
            assertEquals(16, metadata.bitDepth());
        }
    }

    @Test
    void realFixture_decodesFirstWindow() throws Exception {
        Path fixture = Path.of("src/test/resources/7867.230815161432.sud");
        if (!Files.exists(fixture)) {
            return;
        }
        try (X3Decoder decoder = new X3Decoder(fixture)) {
            assertEquals(48_000, decoder.metadata().sampleRate());
            assertEquals(1, decoder.metadata().channels());
            assertEquals(172_813_552L, decoder.chunkIndex().totalSamples());

            int n = 4096;
            short[] pcm = new short[n];
            int got = decoder.decodeSamplesInt(0L, n, pcm);
            assertEquals(n, got);

            // Not all zeros (filter state of first chunk is non-zero ambient)
            boolean anyNonZero = false;
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (short s : pcm) {
                if (s != 0) {
                    anyNonZero = true;
                }
                min = Math.min(min, s);
                max = Math.max(max, s);
            }
            assertTrue(anyNonZero, "decoded PCM should not be all zeros");
            assertTrue(max > min, "decoded PCM should have dynamic range");

            float[] floats = new float[n];
            assertEquals(n, decoder.decodeSamplesFloat(0L, n, floats));
            assertEquals(pcm[0] / 32768.0f, floats[0], 1e-6f);
        }
    }

    @Test
    void parseBlockLen_readsCfg() {
        assertEquals(16, X3Decoder.parseBlockLen("<BLKLEN>16</BLKLEN>", 20));
        assertEquals(20, X3Decoder.parseBlockLen("", 20));
    }

    @Test
    void realFixture_parallelMatchesSequential() throws Exception {
        Path fixture = Path.of("src/test/resources/7867.230815161432.sud");
        if (!Files.exists(fixture)) {
            return;
        }
        int n = 48_000; // enough frames to span multiple chunks
        short[] sequential;
        short[] parallel;
        try (X3Decoder seq = new X3Decoder(fixture, DecodeOptions.defaults().withMaxConcurrency(1))) {
            sequential = new short[n];
            assertEquals(n, seq.decodeSamplesInt(0L, n, sequential));
            assertEquals(1, seq.pipeline().maxConcurrency());
        }
        try (X3Decoder par = new X3Decoder(fixture, DecodeOptions.defaults().withMaxConcurrency(4))) {
            parallel = new short[n];
            assertEquals(n, par.decodeSamplesInt(0L, n, parallel));
            assertTrue(par.pipeline().usesSharedLimiter());
            assertEquals(4, par.pipeline().maxConcurrency());
        }
        assertArrayEquals(sequential, parallel);
        boolean anyNonZero = false;
        for (short s : sequential) {
            if (s != 0) {
                anyNonZero = true;
                break;
            }
        }
        assertTrue(anyNonZero);
    }

    @Test
    void main_doesNotThrow() {
        assertDoesNotThrow(() -> X3Decoder.main(new String[0]));
    }
}
