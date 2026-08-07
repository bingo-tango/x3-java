package edu.cornell.raven.core.audio.x3.sud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkTypeTest {

    @Test
    void fromId_mapsKnownAcousticType() {
        assertEquals(ChunkType.ACOUSTIC_AUDIO, ChunkType.fromId((byte) 0x41));
    }

    @Test
    void fromId_unknownFallsBack() {
        assertEquals(ChunkType.UNKNOWN, ChunkType.fromId((byte) 0x7F));
    }
}
