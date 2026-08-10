package edu.cornell.raven.core.audio.x3a.sud;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 1: Maps a {@code .SUD} file into an off-heap {@link MemorySegment}
 * without copying payload bytes onto the JVM heap, and extracts global
 * device/audio configuration from the file's metadata/event records.
 *
 * <p>Record framing and text encoding below were empirically reverse-engineered
 * from {@code src/test/resources/7867.230815161432.sud} (no vendor spec was
 * available). Every record in the file, metadata or binary audio chunk alike,
 * shares this framing:
 * <pre>
 * offset  0: short  sync            (always 0x52, 0xA9)
 * offset  2: short  reserved0       (always 0x00, 0x00 in the fixture)
 * offset  4: short  payloadLength   (little-endian, bytes following the header)
 * offset  6: short  recordType      (1 == metadata/event record; any other value
 *                                    marks a binary acoustic-audio chunk, and is
 *                                    itself that chunk's decoded sample count —
 *                                    see {@link ChunkIndex}, which indexes those
 *                                    chunks. This class skips them via
 *                                    payloadLength without decoding.)
 * offset  8: int    sessionId       (opaque; constant across a file's metadata run)
 * offset 12: short  sequence        (opaque; not needed for Phase 1)
 * offset 14: byte[6] recordTag      (opaque; not needed for Phase 1)
 * offset 20: payload (payloadLength bytes)
 * </pre>
 * Metadata records appear both before the first audio chunk (device tags,
 * decimation/codec {@code <CFG>} chain) and after the last one (an end-of-session
 * {@code <EVENT>}); both runs are captured.
 * Metadata/event payloads are plain ASCII XML-like text ({@code <EVENT>...},
 * {@code <CFG ...>...}) stored with every adjacent byte pair swapped, e.g. the
 * bytes for {@code "<EVENT>"} are stored as {@code "E<EVTN..."}.
 *
 * <p>{@link FileMetadata#xmlConfig()} concatenates every decoded metadata/event
 * record in file order (device tags, config blocks, etc.) under a single
 * synthetic {@code <SUD_METADATA>} root, so it is the complete recovered
 * metadata document rather than just the {@code <CFG>} fragments. Callers
 * needing to persist it (e.g. as a sidecar file next to a decoded WAV) can
 * simply {@code Files.writeString(path, metadata.xmlConfig())}.
 */
public final class SudFileMapper implements AutoCloseable {

    static final long SYNC_SEARCH_WINDOW = 65_536L;

    private static final Pattern CFG_TAG = Pattern.compile("<CFG\\b");

    private static final FileMetadata DEFAULT_METADATA = new FileMetadata(576_000, 1, 16, "UNKNOWN", "");

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
     * Walks every record in the file, decoding metadata/event records (both the
     * leading configuration run and any trailing end-of-session records after the
     * binary acoustic-audio chunks) to recover device tags and audio configuration.
     * Non-metadata records are skipped via their payload length without decoding.
     * Falls back to typical SoundTrap defaults if no records are found.
     */
    public FileMetadata parseHeader() {
        long fileSize = mappedFile.byteSize();
        long searchLimit = Math.min(fileSize, SYNC_SEARCH_WINDOW);
        long pos = RecordHeader.findFirstSync(mappedFile, searchLimit);
        if (pos < 0) {
            return DEFAULT_METADATA;
        }

        String deviceId = null;
        StringBuilder xmlConfig = new StringBuilder();
        int sampleRate = -1;
        int channels = -1;
        int bitDepth = -1;
        int fallbackSampleRate = -1;
        int fallbackChannels = -1;
        int fallbackBitDepth = -1;

        while (pos + RecordHeader.BYTES <= fileSize) {
            if (!RecordHeader.hasSyncAt(mappedFile, pos)) {
                break;
            }

            int payloadLength = RecordHeader.payloadLength(mappedFile, pos);
            int recordType = RecordHeader.sampleCountOrRecordType(mappedFile, pos);
            long payloadStart = pos + RecordHeader.BYTES;

            if (payloadStart + payloadLength > fileSize) {
                break;
            }

            if (RecordHeader.isMetadata(recordType)) {
                String text = decodeSwappedText(payloadStart, payloadLength);
                xmlConfig.append(text);

                if (deviceId == null) {
                    String hardwareId = extractTagText(text, "HARDWARE_ID");
                    if (hardwareId != null) {
                        deviceId = hardwareId;
                    }
                }

                if (CFG_TAG.matcher(text).find()) {
                    if (text.contains("FTYPE=\"wav\"")) {
                        sampleRate = extractTagInt(text, "FS");
                        channels = extractTagInt(text, "NCHS");
                        bitDepth = extractTagInt(text, "NBITS");
                    } else if (fallbackSampleRate < 0 && text.contains("AUDIO")) {
                        fallbackSampleRate = extractTagInt(text, "FS");
                        fallbackChannels = extractTagInt(text, "NCHS");
                        fallbackBitDepth = extractTagInt(text, "NBITS");
                    }
                }
            }

            pos = payloadStart + payloadLength;
        }

        if (sampleRate < 0) {
            sampleRate = fallbackSampleRate;
            channels = fallbackChannels;
            bitDepth = fallbackBitDepth;
        }
        // Wrap the concatenated <EVENT>/<CFG> fragments in a synthetic root so the result is one
        // well-formed XML document, suitable for writing out as a sidecar file (e.g. Phase 6's
        // WAV export writing the full recovered metadata alongside the decoded audio).
        String fullXml = xmlConfig.isEmpty() ? "" : "<SUD_METADATA>\n" + xmlConfig + "</SUD_METADATA>\n";
        if (sampleRate < 0) {
            return new FileMetadata(576_000, 1, 16, deviceId == null ? "UNKNOWN" : deviceId, fullXml);
        }
        return new FileMetadata(sampleRate, channels, bitDepth, deviceId == null ? "UNKNOWN" : deviceId, fullXml);
    }

    private String decodeSwappedText(long offset, int length) {
        byte[] raw = mappedFile.asSlice(offset, length).toArray(ValueLayout.JAVA_BYTE);
        byte[] swapped = new byte[raw.length];
        int evenLength = raw.length - (raw.length % 2);
        for (int i = 0; i < evenLength; i += 2) {
            swapped[i] = raw[i + 1];
            swapped[i + 1] = raw[i];
        }
        if (raw.length % 2 != 0) {
            swapped[raw.length - 1] = raw[raw.length - 1];
        }
        // Records are padded to an even byte count with a trailing NUL before the pair-swap,
        // which the swap can shift into the middle of the text; strip it rather than emit it
        // into a document callers may write straight to disk as XML.
        return new String(swapped, StandardCharsets.US_ASCII).replace("\u0000", "");
    }

    private static String extractTagText(String xml, String tag) {
        Matcher matcher = Pattern.compile("<" + tag + "[^>]*>\\s*(.*?)\\s*</" + tag + ">").matcher(xml);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static int extractTagInt(String xml, String tag) {
        String value = extractTagText(xml, tag);
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public void close() {
        arena.close();
    }
}
