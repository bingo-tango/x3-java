package edu.cornell.raven.x3a;

import edu.cornell.raven.x3a.internal.X3FrameHeader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.ByteBuffer;

/// Opens X3 audio by content rather than by file extension, returning the right
/// [X3StreamingDecoder] for what the file actually is.
///
/// SoundTrap tooling is inconsistent about naming — bare X3 archives and `.SUD` containers
/// both turn up under `.x3a`, `.sud`, and other suffixes — so hosts should sniff instead of
/// trusting the name. This is the entry point most consumers want; construct
/// [X3ArchiveStreamingDecoder] or [edu.cornell.raven.x3a.sud.SudStreamingDecoder] directly
/// only when the container is already known.
///
/// Also the home of [#readHeader(Path)], for hosts that want format metadata and no samples.
///
/// For a whole-file decode into one buffer, use [X3BulkDecoder] instead.
public final class X3Streams {

    private X3Streams() {
    }

    /// Opens `path` with [DecodeOptions#defaults()] concurrency.
    ///
    /// @throws X3FormatException if the file is neither a readable X3 archive nor a `.SUD` container
    /// @throws IOException if the file cannot be opened or mapped
    public static X3StreamingDecoder open(Path path) throws IOException {
        return open(path, DecodeOptions.defaults());
    }

    /// Opens `path` as an archive if it starts with the `X3ARCHIV` magic, otherwise as a `.SUD`
    /// container. Container-specific framing in `options` is overridden by whichever reader is
    /// chosen; concurrency settings are honoured either way.
    ///
    /// @throws X3FormatException if the file is neither a readable X3 archive nor a `.SUD` container
    /// @throws IOException if the file cannot be opened or mapped
    public static X3StreamingDecoder open(Path path, DecodeOptions options) throws IOException {
        return isArchive(path)
                ? new X3ArchiveStreamingDecoder(path, options)
                : new edu.cornell.raven.x3a.sud.SudStreamingDecoder(path, options);
    }

    /// Reads format metadata without decoding any PCM.
    ///
    /// Opens `path` with [#open(Path)] — so the same magic sniffing applies — and reports what
    /// the resulting decoder knows about the stream, then closes it. Only frame headers are
    /// walked, so cost scales with frame count rather than file size.
    ///
    /// @throws X3FormatException if the file is neither a readable archive nor a `.SUD` container
    /// @throws IOException if the file cannot be read
    public static X3Header readHeader(Path path) throws IOException {
        try (X3StreamingDecoder decoder = open(path)) {
            return new X3Header(decoder.sampleRate(), decoder.channels(), decoder.bitDepth(),
                    decoder.totalSamples(), decoder.deviceId());
        }
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

    /// Format metadata for one X3 file, as reported by [#readHeader(Path)] — everything a host
    /// needs to build an output header, with no PCM decoded.
    public record X3Header(
            /// Sample rate in Hz
            int sampleRate,
            /// Channel count
            int channels,
            /// Bits per sample
            int bitDepth,
            /// Total audio frames
            long frames,
            /// Device identifier from metadata, or [X3StreamingDecoder#UNKNOWN_DEVICE_ID]
            String deviceId
    ) {
    }
}
