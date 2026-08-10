package edu.cornell.raven.core.audio.x3a.sud;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Shared 20-byte record header framing for every record in a {@code .SUD} file
 * (metadata/event record or binary audio chunk alike). See {@link SudFileMapper}'s
 * class doc for the empirically reverse-engineered field layout.
 *
 * <p>Factored out of {@link SudFileMapper} so {@link ChunkIndex}'s Phase 2 record
 * walk shares this framing instead of re-deriving it, avoiding the kind of drift
 * that broke the {@code X3Decoder --> SudFileMapper} dependency before the Phase 1
 * fix.
 */
final class RecordHeader {

    static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN).withName("sync"),
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN).withName("reserved0"),
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN).withName("payloadLength"),
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN).withName("recordType"),
            MemoryLayout.sequenceLayout(12, ValueLayout.JAVA_BYTE).withName("opaqueTail"));

    static final long BYTES = LAYOUT.byteSize();

    private static final VarHandle PAYLOAD_LENGTH =
            LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("payloadLength"));
    private static final VarHandle RECORD_TYPE =
            LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("recordType"));

    private static final byte SYNC_BYTE_0 = 0x52;
    private static final byte SYNC_BYTE_1 = (byte) 0xA9;

    /**
     * Sentinel value of the header's 3rd short field for metadata/event records.
     * For every other record it is instead the record's decoded sample count
     * (always a multiple of {@code BLKLEN=16}) — confirmed by summing this field
     * across the real fixture and matching OceanInstruments' own reported
     * {@code SampleCount} exactly. It is not a chunk-type enum.
     */
    static final int METADATA_RECORD_TYPE = 1;

    private RecordHeader() {
    }

    static boolean hasSyncAt(MemorySegment segment, long pos) {
        return segment.get(ValueLayout.JAVA_BYTE, pos) == SYNC_BYTE_0
                && segment.get(ValueLayout.JAVA_BYTE, pos + 1) == SYNC_BYTE_1;
    }

    static int payloadLength(MemorySegment segment, long pos) {
        return Short.toUnsignedInt((short) PAYLOAD_LENGTH.get(segment, pos));
    }

    /**
     * {@code 1} for metadata/event records; for audio chunks, the decoded sample
     * count carried in this chunk.
     */
    static int sampleCountOrRecordType(MemorySegment segment, long pos) {
        return Short.toUnsignedInt((short) RECORD_TYPE.get(segment, pos));
    }

    static boolean isMetadata(int sampleCountOrRecordType) {
        return sampleCountOrRecordType == METADATA_RECORD_TYPE;
    }

    /**
     * Scans forward from byte 0 for the first {@code 0x52, 0xA9} sync marker within
     * {@code limit} bytes, returning {@code -1} if none is found.
     */
    static long findFirstSync(MemorySegment segment, long limit) {
        for (long i = 0; i + 2 <= limit; i++) {
            if (hasSyncAt(segment, i)) {
                return i;
            }
        }
        return -1;
    }
}
