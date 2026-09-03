package edu.cornell.raven.x3a;

import edu.cornell.raven.x3a.internal.ArchiveIndex;
import edu.cornell.raven.x3a.internal.ChunkPipeline;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Whole-archive decode: one `.x3a` image in, one interleaved PCM buffer out.
///
/// The bulk counterpart to [X3StreamingDecoder]. Use this when you want the entire file's
/// samples at once and it comfortably fits in memory; use [X3Streams#open] instead for large
/// files, where bounded windows avoid ever holding the whole decode.
///
/// Both share one implementation — [ArchiveIndex] for framing, [ChunkPipeline] for the
/// parallel frame decode — so bulk and streaming cannot drift apart. Because every frame of a
/// whole-file read lies entirely inside the requested window, the pipeline decodes each one
/// straight into the output buffer: no per-frame scratch, no copy.
///
/// Payload CRCs are not verified by default; pass
/// [DecodeOptions#withVerifyPayloadCrc(boolean)] to restore that check. Frame headers are
/// always verified.
public final class X3BulkDecoder {

    private X3BulkDecoder() {
    }

    /// Decodes the archive at `path` using [DecodeOptions#defaults()].
    ///
    /// @throws X3FormatException if the file is not a well-formed X3 archive
    /// @throws IOException if the file cannot be read
    public static DecodedArchive decode(Path path) throws IOException {
        return decode(path, DecodeOptions.defaults());
    }

    /// Decodes the archive at `path`, memory-mapping rather than reading it onto the heap.
    ///
    /// @throws X3FormatException if the file is not a well-formed X3 archive
    /// @throws IOException if the file cannot be read
    public static DecodedArchive decode(Path path, DecodeOptions options) throws IOException {
        // Shared, not confined: the pipeline's chunk tasks run on virtual threads, and a confined
        // arena rejects access from any thread but the one that created it.
        try (Arena arena = Arena.ofShared();
             FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            MemorySegment mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
            return decode(mapped, options, path.toString());
        }
    }

    /// Decodes an in-memory archive image using [DecodeOptions#defaults()].
    ///
    /// @throws X3FormatException if the image is not a well-formed X3 archive
    public static DecodedArchive decode(byte[] archive) throws X3FormatException {
        return decode(archive, DecodeOptions.defaults());
    }

    /// Decodes an in-memory archive image. The array is wrapped, not copied.
    ///
    /// @throws X3FormatException if the image is not a well-formed X3 archive
    public static DecodedArchive decode(byte[] archive, DecodeOptions options) throws X3FormatException {
        if (archive == null) {
            throw new IllegalArgumentException("archive must not be null");
        }
        return decode(MemorySegment.ofArray(archive), options, "archive image");
    }

    private static DecodedArchive decode(MemorySegment archive, DecodeOptions options, String source)
            throws X3FormatException {
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        // Archive frames are never pair-swapped and carry no container record header.
        DecodeOptions archiveOpts = options.withPayloadHeaderBytes(0).withSudPayload(false);

        ArchiveIndex index;
        try {
            index = ArchiveIndex.build(archive, X3FrameEncoder.DEFAULT_BLOCK_LEN,
                    X3FrameEncoder.DEFAULT_RICE_ORDERS, archiveOpts.verifyPayloadCrc());
        } catch (IllegalArgumentException e) {
            throw new X3FormatException("not a readable X3 archive: " + source, e);
        }

        int channels = index.channels();
        long totalFrames = index.totalSamples();
        long totalSamples = totalFrames * channels;
        if (totalSamples > Integer.MAX_VALUE) {
            throw new X3FormatException("archive too large to decode in one buffer: "
                    + totalSamples + " samples; use X3Streams.open instead");
        }
        if (totalFrames == 0) {
            return new DecodedArchive(index.sampleRate(), channels, new short[0], index.xml());
        }

        // totalSamples fits an int, so totalFrames does too: the whole file is one window.
        short[] pcm = new short[(int) totalSamples];
        ChunkPipeline pipeline = new ChunkPipeline(archive, index.table(), index.frameCount(),
                totalFrames, new X3FrameDecoder(index.blockLen(), index.riceOrders()), channels,
                archiveOpts);
        try {
            pipeline.decodeWindowInt(0, (int) totalFrames, pcm);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new X3FormatException("corrupt X3 archive data in " + source, e);
        }

        return new DecodedArchive(index.sampleRate(), channels, pcm, index.xml());
    }

    /// Interleaved PCM plus the stream metadata recovered from the archive's config frame.
    public record DecodedArchive(
            /// Sample rate in Hz, parsed from the embedded `<FS>` config.
            int sampleRate,
            /// Channel count, taken from the first data frame's header.
            int channels,
            /// Interleaved decoded PCM samples.
            short[] pcm,
            /// Embedded `<X3ARCH>`/`<CFG>` config XML, recovered verbatim from the archive.
            String xml
    ) {
        /// `pcm.length / channels`.
        public int frames() {
            return pcm.length / channels;
        }
    }
}
