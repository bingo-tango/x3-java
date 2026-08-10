package edu.cornell.raven.core.audio.x3a.sud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SudFileMapperTest {

    @TempDir
    Path tempDir;

    @Test
    void mapsFileAndParsesStubHeader() throws Exception {
        Path sud = tempDir.resolve("sample.sud");
        Files.write(sud, new byte[256]);

        try (SudFileMapper mapper = new SudFileMapper(sud)) {
            assertEquals(256L, mapper.size());
            assertTrue(mapper.mappedFile().byteSize() >= 256L);

            FileMetadata metadata = mapper.parseHeader();
            assertEquals(576_000, metadata.sampleRate());
            assertEquals(1, metadata.channels());
            assertEquals(16, metadata.bitDepth());
        }
    }
}
