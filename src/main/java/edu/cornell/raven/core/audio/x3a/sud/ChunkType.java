package edu.cornell.raven.core.audio.x3a.sud;

/**
 * Identifiers for framed binary chunks inside a SoundTrap {@code .SUD} container.
 */
public enum ChunkType {
    ACOUSTIC_AUDIO((byte) 0x41),
    TELEMETRY((byte) 0x42),
    METADATA_XML((byte) 0x43),
    UNKNOWN((byte) 0x00);

    private final byte id;

    ChunkType(byte id) {
        this.id = id;
    }

    public byte id() {
        return id;
    }

    public static ChunkType fromId(byte id) {
        for (ChunkType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
