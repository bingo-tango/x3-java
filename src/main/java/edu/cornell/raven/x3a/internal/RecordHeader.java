package edu.cornell.raven.x3a.internal;

import edu.cornell.raven.x3a.sud.SudFileMapper;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/// Shared 20-byte record header framing for every record in a `.SUD` file
/// (metadata/event record or binary audio chunk alike). See [SudFileMapper]'s
/// class doc for the empirically reverse-engineered field layout.
///
/// Factored out of [SudFileMapper] so [ChunkIndex]'s record walk shares this
/// framing instead of re-deriving it and risking drift between the two.
public final class RecordHeader {

    static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN).withName("sync"),
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN).withName("reserved0"),
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN).withName("payloadLength"),
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN).withName("recordType"),
            MemoryLayout.sequenceLayout(12, ValueLayout.JAVA_BYTE).withName("opaqueTail"));

    public static final long BYTES = LAYOUT.byteSize();

    private static final VarHandle PAYLOAD_LENGTH =
            LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("payloadLength"));
    private static final VarHandle RECORD_TYPE =
            LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("recordType"));

    private static final byte SYNC_BYTE_0 = 0x52;
    private static final byte SYNC_BYTE_1 = (byte) 0xA9;

    /// Sentinel value of the header's 3rd short field for metadata/event records.
    /// For every other record it is instead the record's decoded sample count
    /// (always a multiple of `BLKLEN=16`) — confirmed by summing this field across
    /// the real fixture and matching OceanInstruments' own reported `SampleCount`
    /// exactly. It is not a chunk-type enum.
    static final int METADATA_RECORD_TYPE = 1;

    private RecordHeader() {
    }

    public static boolean hasSyncAt(MemorySegment segment, long pos) {
        return segment.get(ValueLayout.JAVA_BYTE, pos) == SYNC_BYTE_0
                && segment.get(ValueLayout.JAVA_BYTE, pos + 1) == SYNC_BYTE_1;
    }

    public static int payloadLength(MemorySegment segment, long pos) {
        return Short.toUnsignedInt((short) PAYLOAD_LENGTH.get(segment, pos));
    }

    /// `1` for metadata/event records; for audio chunks, the decoded sample count
    /// carried in this chunk.
    public static int sampleCountOrRecordType(MemorySegment segment, long pos) {
        return Short.toUnsignedInt((short) RECORD_TYPE.get(segment, pos));
    }

    public static boolean isMetadata(int sampleCountOrRecordType) {
        return sampleCountOrRecordType == METADATA_RECORD_TYPE;
    }

    /// Scans forward from byte 0 for the first `0x52, 0xA9` sync marker within
    /// `limit` bytes, returning `-1` if none is found.
    public static long findFirstSync(MemorySegment segment, long limit) {
        for (long i = 0; i + 2 <= limit; i++) {
            if (hasSyncAt(segment, i)) {
                return i;
            }
        }
        return -1;
    }
}
