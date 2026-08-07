package edu.cornell.raven.core.audio.x3.sud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileMetadataTest {

    @Test
    void storesHeaderFields() {
        FileMetadata metadata = new FileMetadata(576_000, 2, 16, "ST600", "<cfg/>");

        assertEquals(576_000, metadata.sampleRate());
        assertEquals(2, metadata.channels());
        assertEquals(16, metadata.bitDepth());
        assertEquals("ST600", metadata.deviceId());
        assertEquals("<cfg/>", metadata.xmlConfig());
    }
}
