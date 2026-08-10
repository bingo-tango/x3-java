package edu.cornell.raven.core.audio.x3a.sud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class X3DecoderTest {

    @TempDir
    Path tempDir;

    @Test
    void openDecodeClose_stubPaths() throws Exception {
        Path sud = tempDir.resolve("tiny.sud");
        Files.write(sud, new byte[256]);

        try (X3Decoder decoder = new X3Decoder(sud)) {
            short[] ints = new short[32];
            float[] floats = new float[32];

            assertEquals(32, decoder.decodeSamplesInt(0L, 32, ints));
            assertEquals(32, decoder.decodeSamplesFloat(0L, 32, floats));
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
    void main_doesNotThrow() {
        assertDoesNotThrow(() -> X3Decoder.main(new String[0]));
    }
}
