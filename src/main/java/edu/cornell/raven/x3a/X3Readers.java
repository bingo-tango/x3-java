package edu.cornell.raven.x3a;

import edu.cornell.raven.x3a.internal.X3FrameHeader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.ByteBuffer;

/// Opens X3 audio by content rather than by file extension, returning the right
/// [X3SampleReader] for what the file actually is.
///
/// SoundTrap tooling is inconsistent about naming — bare X3 archives and `.SUD` containers
/// both turn up under `.x3a`, `.sud`, and other suffixes — so hosts should sniff instead of
/// trusting the name. This is the entry point most consumers want; construct
/// [X3ArchiveDecoder] or [edu.cornell.raven.x3a.sud.X3Decoder] directly only when the
/// container is already known.
public final class X3Readers {

    private X3Readers() {
    }

    /// Opens `path` with [DecodeOptions#defaults()] concurrency.
    ///
    /// @throws X3FormatException if the file is neither a readable X3 archive nor a `.SUD` container
    /// @throws IOException if the file cannot be opened or mapped
    public static X3SampleReader open(Path path) throws IOException {
        return open(path, DecodeOptions.defaults());
    }

    /// Opens `path` as an archive if it starts with the `X3ARCHIV` magic, otherwise as a `.SUD`
    /// container. Container-specific framing in `options` is overridden by whichever reader is
    /// chosen; concurrency settings are honoured either way.
    ///
    /// @throws X3FormatException if the file is neither a readable X3 archive nor a `.SUD` container
    /// @throws IOException if the file cannot be opened or mapped
    public static X3SampleReader open(Path path, DecodeOptions options) throws IOException {
        return isArchive(path)
                ? new X3ArchiveDecoder(path, options)
                : new edu.cornell.raven.x3a.sud.X3Decoder(path, options);
    }

    /// Whether `path` opens with the `X3ARCHIV` magic of a bare X3 archive. A file too short to
    /// hold the magic reads as "not an archive", leaving the container reader to report why.
    ///
    /// @throws IOException if the file cannot be read
    public static boolean isArchive(Path path) throws IOException {
        byte[] magic = X3FrameHeader.ARCHIVE_ID;
        if (Files.size(path) < magic.length) {
            return false;
        }
        ByteBuffer head = ByteBuffer.allocate(magic.length);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            while (head.hasRemaining()) {
                if (channel.read(head) < 0) {
                    return false;
                }
            }
        }
        byte[] got = head.array();
        for (int i = 0; i < magic.length; i++) {
            if (got[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
