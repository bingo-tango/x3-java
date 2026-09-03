package edu.cornell.raven.x3a;

import edu.cornell.raven.x3a.internal.ArchiveIndex;
import edu.cornell.raven.x3a.internal.ChunkPipeline;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Random-access decoder for a bare `.x3a` archive — the archive counterpart to
/// [edu.cornell.raven.x3a.sud.X3Decoder], and the streaming alternative to
/// [X3Files#decodeArchive(byte[])].
///
/// Construction maps the file off-heap and indexes its frame headers, so a host can seek to
/// an arbitrary sample and decode a bounded window without ever holding the whole archive —
/// compressed or decoded — in memory. That matters for hosts that open one decoder per cache
/// block over multi-gigabyte recordings, where a whole-file decode per block is not viable.
///
/// Decode work is delegated to [ChunkPipeline], so windows spanning several frames decode in
/// parallel under the same limits as `.SUD` decode; see [DecodeOptions].
///
/// Instances are safe to use from one thread at a time; [#decodeSamplesFloat] mutates shared
/// scratch, so concurrent readers need one decoder each.
public final class X3ArchiveDecoder implements X3SampleReader {

    private static final float SCALE_TO_UNIT = 1.0f / 32768.0f;

    /// X3 codes 16-bit PCM; the archive format carries no other depth.
    private static final int BIT_DEPTH = 16;

    private final Arena arena;
    private final MemorySegment mappedFile;
    private final ArchiveIndex index;
    private final ChunkPipeline pipeline;
    private final int channels;

    /// Reusable short→float staging buffer for [#decodeSamplesFloat]; grows once, then stays sized.
    private short[] floatScratch = new short[0];

    private boolean closed;

    /// Opens `archivePath` with [DecodeOptions#defaults()] concurrency.
    ///
    /// @throws X3FormatException if the file is not a well-formed X3 archive
    /// @throws IOException if the file cannot be opened or mapped
    public X3ArchiveDecoder(Path archivePath) throws IOException {
        this(archivePath, DecodeOptions.defaults());
    }

    /// Opens `archivePath`, mapping it and indexing its frames.
    ///
    /// Archive payloads are never pair-swapped and carry no container record header, so those
    /// parts of `options` are forced off; only concurrency tuning is caller-controlled.
    ///
    /// @throws X3FormatException if the file is not a well-formed X3 archive
    /// @throws IOException if the file cannot be opened or mapped
    public X3ArchiveDecoder(Path archivePath, DecodeOptions options) throws IOException {
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        DecodeOptions archiveOpts = options.withPayloadHeaderBytes(0).withSudPayload(false);

        Arena openArena = Arena.ofShared();
        try {
            try (FileChannel channel = FileChannel.open(archivePath, StandardOpenOption.READ)) {
                this.mappedFile = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), openArena);
            }
            try {
                this.index = ArchiveIndex.build(mappedFile, X3AudioEncoder.DEFAULT_BLOCK_LEN,
                        X3AudioEncoder.DEFAULT_RICE_ORDERS);
            } catch (IllegalArgumentException e) {
                throw new X3FormatException("not a readable X3 archive: " + archivePath, e);
            }
        } catch (IOException | RuntimeException e) {
            openArena.close();
            throw e;
        }
        this.arena = openArena;
        this.channels = index.channels();
        this.pipeline = new ChunkPipeline(
                mappedFile,
                index.table(),
                index.frameCount(),
                index.totalSamples(),
                new X3AudioDecoder(index.blockLen(), index.riceOrders()),
                channels,
                archiveOpts);
    }

    @Override
    public int sampleRate() {
        return index.sampleRate();
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public int bitDepth() {
        return BIT_DEPTH;
    }

    @Override
    public long totalSamples() {
        return index.totalSamples();
    }

    /// Config-frame XML recovered from the archive, carrying the `<FS>` / `<BLKLEN>` / `<CODES>`
    /// settings decode was configured from.
    public String xmlConfig() {
        return index.xml();
    }

    @Override
    public int decodeSamplesInt(long startSample, int length, short[] dest) throws IOException {
        try {
            return pipeline.decodeWindowInt(startSample, length, dest);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new X3FormatException("corrupt X3 archive data at sample " + startSample, e);
        }
    }

    @Override
    public int decodeSamplesFloat(long startSample, int length, float[] dest) throws IOException {
        if (length <= 0) {
            return 0;
        }
        int need = length * channels;
        if (floatScratch.length < need) {
            floatScratch = new short[need];
        }
        int frames = decodeSamplesInt(startSample, length, floatScratch);
        int samples = frames * channels;
        for (int i = 0; i < samples; i++) {
            dest[i] = floatScratch[i] * SCALE_TO_UNIT;
        }
        return frames;
    }

    /// Unmaps the archive. Idempotent; decoding after this throws [IllegalStateException] from
    /// the closed arena.
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            arena.close();
        }
    }
}
