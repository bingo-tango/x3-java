package edu.cornell.raven.core.audio.x3;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitstreamReaderTest {

    @Test
    void readBits_readsPackedBytes() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment payload = arena.allocate(2);
            payload.set(ValueLayout.JAVA_BYTE, 0, (byte) 0b1011_0000);
            payload.set(ValueLayout.JAVA_BYTE, 1, (byte) 0b1111_0000);

            BitstreamReader reader = new BitstreamReader(payload);
            assertEquals(0b1011, reader.readBits(4));
            assertEquals(0b0000, reader.readBits(4));
            assertTrue(reader.hasRemaining());
        }
    }

    @Test
    void readBits_rejectsInvalidWidth() {
        try (Arena arena = Arena.ofConfined()) {
            BitstreamReader reader = new BitstreamReader(arena.allocate(1));
            assertThrows(IllegalArgumentException.class, () -> reader.readBits(0));
            assertThrows(IllegalArgumentException.class, () -> reader.readBits(33));
        }
    }
}
