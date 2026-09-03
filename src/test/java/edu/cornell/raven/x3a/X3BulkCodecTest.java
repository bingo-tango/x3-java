package edu.cornell.raven.x3a;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Covers whole-archive encode/decode ([X3BulkEncoder] / [X3BulkDecoder]), the metadata-only
/// read on [X3Streams], and how each responds to corrupt input.
class X3BulkCodecTest {

    @TempDir
    Path tmp;

    /// Byte offset of the first data frame's header: archive id, then the config frame's header
    /// and its XML payload.
    private static int firstDataHeaderOffset(String xml) {
        return 8 + 20 + xml.length();
    }

    private Path writeArchive(int sampleRate, int channels, int frames) throws Exception {
        short[] pcm = new short[frames * channels];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (Math.sin(i * 0.01) * 12000);
        }
        Path wav = tmp.resolve("in.wav");
        Path x3a = tmp.resolve("in.x3a");
        WavPcm.write(wav, sampleRate, channels, pcm);
        X3Files.wavToX3a(wav, x3a);
        return x3a;
    }

    @Test
    void encode_writesArchiveIdAndConfigXml() {
        short[] pcm = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20};
        byte[] arch = X3BulkEncoder.encode(pcm, pcm.length, 1, 16000, new X3FrameEncoder());

        assertEquals('X', (char) arch[0]);
        assertEquals('3', (char) arch[1]);
        String asText = new String(arch, 0, Math.min(arch.length, 400));
        assertTrue(asText.contains("X3ARCHIV"));
        assertTrue(asText.contains("BLKLEN"));
    }

    @Test
    void decodeFromPathAndFromBytesAgree() throws Exception {
        Path x3a = writeArchive(48000, 2, 24000);

        X3BulkDecoder.DecodedArchive fromPath = X3BulkDecoder.decode(x3a);
        X3BulkDecoder.DecodedArchive fromBytes = X3BulkDecoder.decode(Files.readAllBytes(x3a));

        assertEquals(fromPath.sampleRate(), fromBytes.sampleRate());
        assertEquals(fromPath.channels(), fromBytes.channels());
        assertEquals(fromPath.xml(), fromBytes.xml());
        assertArrayEquals(fromPath.pcm(), fromBytes.pcm());
    }

    @Test
    void readHeader_extractsMetadataWithoutDecodingPcm() throws Exception {
        Path x3a = writeArchive(48000, 2, 24000);

        X3Streams.X3Header header = X3Streams.readHeader(x3a);

        assertEquals(48000, header.sampleRate());
        assertEquals(2, header.channels());
        assertEquals(16, header.bitDepth());
        assertEquals(24000, header.frames());
        assertEquals(X3StreamingDecoder.UNKNOWN_DEVICE_ID, header.deviceId());
    }

    @Test
    void readHeader_andBulkDecode_reportTheSameStream() throws Exception {
        Path x3a = writeArchive(16000, 1, 32000);

        X3Streams.X3Header header = X3Streams.readHeader(x3a);
        X3BulkDecoder.DecodedArchive archive = X3BulkDecoder.decode(x3a);

        assertEquals(header.frames(), archive.frames());
        assertEquals(header.sampleRate(), archive.sampleRate());
        assertEquals(header.channels(), archive.channels());
    }

    @Test
    void verifyPayloadCrc_doesNotChangeOutputForIntactArchive() throws Exception {
        Path x3a = writeArchive(16000, 1, 32000);

        X3BulkDecoder.DecodedArchive lax = X3BulkDecoder.decode(x3a);
        X3BulkDecoder.DecodedArchive strict = X3BulkDecoder.decode(x3a,
                DecodeOptions.defaults().withVerifyPayloadCrc(true));

        assertArrayEquals(lax.pcm(), strict.pcm());
    }

    @Test
    void corruptPayload_rejectedWhenCrcVerificationEnabled() throws Exception {
        Path x3a = writeArchive(16000, 1, 32000);
        byte[] archive = Files.readAllBytes(x3a);
        // Well inside the first data frame's payload, past its 20-byte header.
        int payloadByte = firstDataHeaderOffset(X3BulkDecoder.decode(archive).xml()) + 24;
        archive[payloadByte] ^= (byte) 0xFF;

        DecodeOptions strict = DecodeOptions.defaults().withVerifyPayloadCrc(true);
        assertThrows(X3FormatException.class, () -> X3BulkDecoder.decode(archive, strict));
    }

    @Test
    void corruptFrameHeader_rejectedByDefault() throws Exception {
        Path x3a = writeArchive(16000, 1, 32000);
        byte[] archive = Files.readAllBytes(x3a);
        // Inside the first data frame's header but past the "x3" key, so the walk reads it as a
        // frame and its header CRC — checked whether or not payload verification is on — fails.
        int headerByte = firstDataHeaderOffset(X3BulkDecoder.decode(archive).xml()) + 5;
        archive[headerByte] ^= (byte) 0xFF;

        assertThrows(X3FormatException.class, () -> X3BulkDecoder.decode(archive));
    }

    /// Enough frames to fan out over several batches, and a signal that mixes Rice and BFP
    /// blocks so payload lengths vary between frames.
    private static short[] multiFramePcm(int channels) {
        int frames = new X3FrameEncoder().samplesPerFrame() * 5 + 137;
        short[] pcm = new short[frames * channels];
        int state = 0x0f1e_2d3c;
        for (int i = 0; i < pcm.length; i++) {
            state = state * 1_103_515_245 + 12_345;
            pcm[i] = (short) (Math.sin(i * 0.003) * 9000 + ((state >> 20) & 0xff));
        }
        return pcm;
    }

    private static byte[] encodeWithConcurrency(short[] pcm, int frames, int channels, String concurrency) {
        String prev = System.setProperty("x3a.encode.maxConcurrency", concurrency);
        try {
            return X3BulkEncoder.encode(pcm, frames, channels, 48000, new X3FrameEncoder());
        } finally {
            if (prev == null) {
                System.clearProperty("x3a.encode.maxConcurrency");
            } else {
                System.setProperty("x3a.encode.maxConcurrency", prev);
            }
        }
    }

    /// Frames are encoded concurrently but must be emitted in order, so the archive has to be
    /// byte-identical to the single-threaded encode.
    @Test
    void parallelEncodeMatchesSequentialByteForByte() {
        for (int channels : new int[] {1, 2}) {
            short[] pcm = multiFramePcm(channels);
            int frames = pcm.length / channels;

            byte[] sequential = encodeWithConcurrency(pcm, frames, channels, "1");
            byte[] parallel = encodeWithConcurrency(pcm, frames, channels, "4");

            assertArrayEquals(sequential, parallel, channels + "-channel archive");
        }
    }

    /// The streaming path shares the frame driver with the heap path; same bytes, no image.
    @Test
    void encodeToStreamMatchesEncodeToArray() throws Exception {
        short[] pcm = multiFramePcm(1);
        int frames = pcm.length;

        byte[] image = X3BulkEncoder.encode(pcm, frames, 1, 48000, new X3FrameEncoder());
        var buffer = new java.io.ByteArrayOutputStream();
        X3BulkEncoder.encodeTo(buffer, pcm, frames, 1, 48000, new X3FrameEncoder());

        assertArrayEquals(image, buffer.toByteArray());
    }

    /// A multi-frame, multi-channel archive must survive the round trip exactly — the check
    /// that concurrent frame encoding never crosses residual state between frames.
    @Test
    void multiFrameRoundTripIsLossless() throws Exception {
        for (int channels : new int[] {1, 2}) {
            short[] pcm = multiFramePcm(channels);
            int frames = pcm.length / channels;

            byte[] archive = X3BulkEncoder.encode(pcm, frames, channels, 48000, new X3FrameEncoder());
            X3BulkDecoder.DecodedArchive decoded = X3BulkDecoder.decode(archive,
                    DecodeOptions.defaults().withVerifyPayloadCrc(true));

            assertEquals(channels, decoded.channels());
            assertEquals(frames, decoded.frames());
            assertArrayEquals(pcm, decoded.pcm(), channels + "-channel round trip");
        }
    }

    @Test
    void nonArchiveRejectedAsFormatError() {
        byte[] junk = "this is not an X3 archive, not even close".getBytes();
        assertThrows(X3FormatException.class, () -> X3BulkDecoder.decode(junk));
    }
}
