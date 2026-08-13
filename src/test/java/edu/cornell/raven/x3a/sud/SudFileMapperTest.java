package edu.cornell.raven.x3a.sud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

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

    @Test
    void parsesRealSoundTrapFixtureMetadata() throws Exception {
        Path sud = Path.of("src/test/resources/7867.230815161432.sud");

        try (SudFileMapper mapper = new SudFileMapper(sud)) {
            FileMetadata metadata = mapper.parseHeader();

            // Ground truth recovered from the fixture's own embedded <CFG FTYPE="wav"> record,
            // which documents the WAV-equivalent config for the X3V2-coded acoustic chunks.
            assertEquals(48_000, metadata.sampleRate());
            assertEquals(1, metadata.channels());
            assertEquals(16, metadata.bitDepth());
            assertEquals("ST600", metadata.deviceId());
            assertTrue(metadata.xmlConfig().startsWith("<SUD_METADATA>"));
            assertTrue(metadata.xmlConfig().contains("FTYPE=\"wav\""));
            assertTrue(metadata.xmlConfig().contains("<HARDWARE_ID>"));
        }
    }

    /**
     * Cross-checks the fully decoded metadata stream against a reference XML log
     * ({@code 7867.230815161432.log.xml}) exported from the same recording by
     * OceanInstruments' own SoundTrap software. That reference wraps its content in
     * an {@code <ST>} root and appends {@code <PROC_EVENT>} entries (offload
     * timestamps, sample count) computed from the decoded audio stream itself,
     * neither of which exists as raw bytes in the {@code .sud} file, so both are
     * stripped before comparing. What remains — every {@code <EVENT>}/{@code <CFG>}
     * record, including the trailing end-of-session {@code <EVENT>} after the
     * binary audio chunks — matches byte-for-byte once whitespace/padding
     * differences from the two tools' formatting are normalized away.
     */
    @Test
    void xmlConfigMatchesOceanInstrumentsReferenceExport() throws Exception {
        Path sud = Path.of("src/test/resources/7867.230815161432.sud");
        Path referenceXml = Path.of("src/test/resources/7867.230815161432.log.xml");

        try (SudFileMapper mapper = new SudFileMapper(sud)) {
            FileMetadata metadata = mapper.parseHeader();

            String ours = normalize(metadata.xmlConfig().replaceAll("</?SUD_METADATA>", ""));

            String reference = Files.readString(referenceXml);
            reference = reference.replaceAll("</?ST>", "");
            reference = reference.replaceAll("(?s)<PROC_EVENT.*?</PROC_EVENT>", "");
            reference = normalize(reference);

            assertEquals(reference, ours);
        }
    }

    private static String normalize(String xml) {
        return Pattern.compile("[\\s\\u0000]+").matcher(xml).replaceAll("");
    }
}
