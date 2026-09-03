package edu.cornell.raven.x3a.sud;

import edu.cornell.raven.x3a.DecodeOptions;
import edu.cornell.raven.x3a.X3FrameDecoder;
import edu.cornell.raven.x3a.X3FormatException;
import edu.cornell.raven.x3a.X3StreamingDecoder;
import edu.cornell.raven.x3a.internal.ChunkIndex;
import edu.cornell.raven.x3a.internal.ChunkPipeline;
import edu.cornell.raven.x3a.internal.RecordHeader;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Random-access streaming decoder for a SoundTrap `.SUD` container, tying together file
/// mapping, chunk indexing, and X3 decode into a single handle for one file.
///
/// The container-format sibling of [edu.cornell.raven.x3a.X3ArchiveStreamingDecoder]; both
/// implement [X3StreamingDecoder], so hosts that only want PCM can accept either.
///
/// Construction eagerly maps the file and builds the chunk index so [#decodeSamplesInt]
/// / [#decodeSamplesFloat] can seek and decode without further I/O setup cost.
public class SudStreamingDecoder implements X3StreamingDecoder {

    private static final Pattern BLKLEN = Pattern.compile("<BLKLEN>\\s*(\\d+)\\s*</BLKLEN>");

    private final SudFileMapper mapper;
    private final MemorySegment mappedFile;

    private final FileMetadata metadata;

    private final ChunkIndex chunkIndex;

    private final X3FrameDecoder frameDecoder;
    private final ChunkPipeline pipeline;
    private final DecodeOptions options;
    private final int channels;

    private boolean closed;

    /// Opens `sudFilePath` with [DecodeOptions#sudDefaults] concurrency.
    ///
    /// @throws IOException if the file cannot be opened or mapped
    public SudStreamingDecoder(Path sudFilePath) throws IOException {
        this(sudFilePath, DecodeOptions.sudDefaults((int) RecordHeader.BYTES));
    }

    /// Opens `sudFilePath`, mapping it, parsing its metadata, and building its chunk
    /// index. SUD container framing (record header skip + pair-swap) is applied
    /// regardless of `options`; only concurrency tuning is caller-controlled.
    ///
    /// @throws IOException if the file cannot be opened or mapped
    public SudStreamingDecoder(Path sudFilePath, DecodeOptions options) throws IOException {
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        // SUD facade always applies container framing + pair-swap; caller may still tune concurrency.
        DecodeOptions sudOpts = options
                .withPayloadHeaderBytes((int) RecordHeader.BYTES)
                .withSudPayload(true);
        this.options = sudOpts;
        this.mapper = new SudFileMapper(sudFilePath);
        this.mappedFile = mapper.mappedFile();
        this.metadata = mapper.parseHeader();
        this.chunkIndex = new ChunkIndex();
        this.chunkIndex.build(mappedFile, metadata.sampleRate());
        this.channels = Math.max(1, metadata.channels());
        int blockLen = parseBlockLen(metadata.xmlConfig(), 16);
        this.frameDecoder = new X3FrameDecoder(blockLen, new int[] {0, 1, 3});
        this.pipeline = new ChunkPipeline(
                mappedFile,
                chunkIndex.table(),
                chunkIndex.chunkCount(),
                chunkIndex.totalSamples(),
                frameDecoder,
                channels,
                sudOpts);
    }

    /// File configuration recovered from the SUD metadata records (sample rate,
    /// channel count, bit depth, device tags) — needed by consumers building output
    /// headers.
    public FileMetadata metadata() {
        return metadata;
    }

    @Override
    public int sampleRate() {
        return metadata.sampleRate();
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public int bitDepth() {
        return metadata.bitDepth();
    }

    @Override
    public long totalSamples() {
        return chunkIndex.totalSamples();
    }

    @Override
    public String deviceId() {
        return metadata.deviceId();
    }

    /// In-memory chunk index, allowing random seeking by sample without `.sudx`
    /// sidecar files. Package-scoped: [ChunkIndex] is an internal type, and
    /// [#totalSamples()] covers what callers outside the library need from it.
    ChunkIndex chunkIndex() {
        return chunkIndex;
    }

    /// Effective decode options, with SUD container framing applied.
    public DecodeOptions options() {
        return options;
    }

    /// Underlying chunk pipeline (tests / diagnostics). Package-scoped, since
    /// [ChunkPipeline] is an internal type.
    ChunkPipeline pipeline() {
        return pipeline;
    }

    /// Decodes `length` frames starting at `startSample` into `destIntBuffer`
    /// (interleaved, `length * channels` capacity from index 0). Caller pre-allocates
    /// the buffer so repeated reads stay allocation-free.
    ///
    /// @return number of frames written
    @Override
    public int decodeSamplesInt(long startSample, int length, short[] destIntBuffer) throws IOException {
        try {
            return pipeline.decodeWindowInt(startSample, length, destIntBuffer);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new X3FormatException("corrupt SUD chunk data at sample " + startSample, e);
        }
    }

    /// Same as [#decodeSamplesInt] but normalizes into `[-1, 1)` floats. The pipeline's
    /// int scratch grows once to the largest requested window, then stays allocation-free.
    ///
    /// @return number of frames written
    @Override
    public int decodeSamplesFloat(long startSample, int length, float[] destFloatBuffer) throws IOException {
        try {
            return pipeline.decodeWindowFloat(startSample, length, destFloatBuffer);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new X3FormatException("corrupt SUD chunk data at sample " + startSample, e);
        }
    }

    static int parseBlockLen(String xml, int defaultLen) {
        if (xml == null || xml.isEmpty()) {
            return defaultLen;
        }
        Matcher m = BLKLEN.matcher(xml);
        if (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v > 0 && v <= 64) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return defaultLen;
    }

    /// Unmaps the underlying file. Idempotent.
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            mapper.close();
        }
    }
}
