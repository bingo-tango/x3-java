package edu.cornell.raven.core.audio.x3a.sud;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Phase 1: Maps a {@code .SUD} file into an off-heap {@link MemorySegment}
 * without copying payload bytes onto the JVM heap.
 */
public final class SudFileMapper implements AutoCloseable {

    private final Arena arena;
    private final MemorySegment mappedFile;

    public SudFileMapper(Path sudFilePath) throws Exception {
        this.arena = Arena.ofConfined();
        try (FileChannel channel = FileChannel.open(sudFilePath, StandardOpenOption.READ)) {
            this.mappedFile = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
        }
    }

    public MemorySegment mappedFile() {
        return mappedFile;
    }

    public long size() {
        return mappedFile.byteSize();
    }

    /**
     * Parses global header fields (sample rate, channels, bit depth, device tags).
     * Stub: returns typical SoundTrap defaults until format layouts are implemented.
     */
    public FileMetadata parseHeader() {
        // TODO: Use VarHandle layouts against mappedFile for exact SoundTrap header fields.
        return new FileMetadata(576_000, 1, 16, "UNKNOWN", "");
    }

    @Override
    public void close() {
        arena.close();
    }
}
