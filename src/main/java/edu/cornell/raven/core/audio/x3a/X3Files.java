package edu.cornell.raven.core.audio.x3a;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple {@code .wav} ↔ {@code .x3a} file conversion for tests and upstream tooling.
 * <p>
 * Pure Java (no libsndfile). Archive layout matches the public X3 archive format
 * (x3-rust / x3new.m): {@code X3ARCHIV} + XML config frame + data frames.
 */
public final class X3Files {

    private static final Pattern FS = Pattern.compile("<FS[^>]*>(\\d+)</FS>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLKLEN = Pattern.compile("<BLKLEN>(\\d+)</BLKLEN>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODES = Pattern.compile(
            "<CODES[^>]*>\\s*RICE(\\d+)\\s*,\\s*RICE(\\d+)\\s*,\\s*RICE(\\d+)\\s*,\\s*BFP\\s*</CODES>",
            Pattern.CASE_INSENSITIVE);

    private X3Files() {
    }

    /**
     * Convert a 16-bit PCM WAV file to an X3 archive ({@code .x3a}).
     * Overwrites {@code x3aPath} if it exists.
     */
    public static void wavToX3a(Path wavPath, Path x3aPath) throws IOException {
        wav_to_x3a(wavPath, x3aPath);
    }

    /**
     * Snake_case alias matching the x3-rust API name.
     */
    public static void wav_to_x3a(Path wavPath, Path x3aPath) throws IOException {
        WavPcm.WavData wav = WavPcm.read(wavPath);
        X3AudioEncoder encoder = new X3AudioEncoder();
        byte[] archive = encodeArchive(
                wav.samples,
                wav.frames,
                wav.channels,
                wav.sampleRate,
                encoder);
        Files.write(x3aPath, archive);
    }

    /**
     * Convert an X3 archive ({@code .x3a}) to a 16-bit PCM WAV file.
     * Overwrites {@code wavPath} if it exists.
     */
    public static void x3aToWav(Path x3aPath, Path wavPath) throws IOException {
        x3a_to_wav(x3aPath, wavPath);
    }

    /**
     * Snake_case alias matching the x3-rust API name.
     */
    public static void x3a_to_wav(Path x3aPath, Path wavPath) throws IOException {
        byte[] all = Files.readAllBytes(x3aPath);
        DecodedArchive dec = decodeArchive(all);
        WavPcm.write(wavPath, dec.sampleRate, dec.channels, dec.pcm);
    }

    /**
     * Build a complete {@code .x3a} byte image from interleaved PCM.
     */
    public static byte[] encodeArchive(short[] pcm, int frames, int channels, int sampleRate,
                                       X3AudioEncoder encoder) {
        if (frames <= 0) {
            throw new IllegalArgumentException("frames must be > 0");
        }
        if (pcm.length < frames * channels) {
            throw new IllegalArgumentException("pcm too short");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(pcm.length + 1024);
        try {
            out.write(X3FrameHeader.ARCHIVE_ID);

            String xml = buildXml(sampleRate, encoder);
            byte[] xmlBytes = xml.getBytes(StandardCharsets.US_ASCII);
            // Even length payload (word align): trim one pad space if odd.
            int xmlLen = xmlBytes.length;
            if ((xmlLen & 1) != 0) {
                xmlLen--;
            }
            int xmlCrc = Crc16.crc(xmlBytes, 0, xmlLen);
            // Archive header frame: source 0, channels 0, samples 0 (metadata).
            out.write(new X3FrameHeader(0, 0, 0, xmlLen, 0L, xmlCrc).encode());
            out.write(xmlBytes, 0, xmlLen);

            int spf = encoder.samplesPerFrame();
            int offFrames = 0;
            while (offFrames < frames) {
                int n = Math.min(spf, frames - offFrames);
                int sampleOff = offFrames * channels;
                BitstreamWriter bp = encoder.encodeFrame(pcm, sampleOff, n, channels);
                byte[] payload = bp.toByteArray();
                out.write(new X3FrameHeader(1, channels, n, payload.length, 0L, bp.crc()).encode());
                out.write(payload);
                offFrames += n;
            }
        } catch (IOException e) {
            // ByteArrayOutputStream does not throw
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /**
     * Decode a full archive image to interleaved PCM + stream metadata.
     * <p>
     * Single pre-sized PCM buffer; frame payloads are zero-copy slices of the archive
     * array (no per-frame arena or chunk list).
     */
    public static DecodedArchive decodeArchive(byte[] archive) {
        if (archive.length < X3FrameHeader.ARCHIVE_ID.length + X3FrameHeader.LENGTH) {
            throw new IllegalArgumentException("archive too small");
        }
        for (int i = 0; i < X3FrameHeader.ARCHIVE_ID.length; i++) {
            if (archive[i] != X3FrameHeader.ARCHIVE_ID[i]) {
                throw new IllegalArgumentException("missing X3ARCHIV id");
            }
        }

        int pos = X3FrameHeader.ARCHIVE_ID.length;
        X3FrameHeader xmlHdr = X3FrameHeader.decode(archive, pos);
        pos += X3FrameHeader.LENGTH;
        if (pos + xmlHdr.payloadLen > archive.length) {
            throw new IllegalArgumentException("XML payload truncated");
        }
        int gotCrc = Crc16.crc(archive, pos, xmlHdr.payloadLen);
        if (gotCrc != xmlHdr.payloadCrc) {
            throw new IllegalArgumentException("XML payload CRC mismatch");
        }
        String xml = new String(archive, pos, xmlHdr.payloadLen, StandardCharsets.US_ASCII);
        pos += xmlHdr.payloadLen;

        int sampleRate = parseInt(FS, xml, 48000);
        int blockLen = parseInt(BLKLEN, xml, X3AudioEncoder.DEFAULT_BLOCK_LEN);
        int[] rice = parseTriple(CODES, xml, X3AudioEncoder.DEFAULT_RICE_ORDERS);
        X3AudioDecoder decoder = new X3AudioDecoder(blockLen, rice);

        // Pass 1: sum PCM samples from frame headers (cheap; exact capacity).
        int channels = 1;
        int totalSamples = 0;
        int scan = pos;
        while (scan + X3FrameHeader.LENGTH <= archive.length) {
            int key = X3FrameHeader.getBe16(archive, scan);
            if (key != X3FrameHeader.KEY) {
                break;
            }
            X3FrameHeader fh = X3FrameHeader.decode(archive, scan);
            scan += X3FrameHeader.LENGTH;
            if (fh.payloadLen <= 0 || scan + fh.payloadLen > archive.length) {
                break;
            }
            if (fh.samples > 0) {
                channels = Math.max(1, fh.channels);
                totalSamples += fh.samples * channels;
            }
            scan += fh.payloadLen;
        }

        short[] pcm = totalSamples > 0 ? new short[totalSamples] : new short[0];
        int out = 0;

        while (pos + X3FrameHeader.LENGTH <= archive.length) {
            int key = X3FrameHeader.getBe16(archive, pos);
            if (key != X3FrameHeader.KEY) {
                break;
            }
            X3FrameHeader fh = X3FrameHeader.decode(archive, pos);
            pos += X3FrameHeader.LENGTH;
            if (fh.payloadLen <= 0 || pos + fh.payloadLen > archive.length) {
                break;
            }
            int pcrc = Crc16.crc(archive, pos, fh.payloadLen);
            if (pcrc != fh.payloadCrc) {
                throw new IllegalArgumentException("frame payload CRC mismatch at " + (pos - X3FrameHeader.LENGTH));
            }

            if (fh.samples <= 0) {
                // metadata frame mid-stream — skip
                pos += fh.payloadLen;
                continue;
            }

            channels = Math.max(1, fh.channels);
            int nSamples = fh.samples * channels;
            // Direct heap range — no Arena / MemorySegment on the archive hot path.
            decoder.decodeChunkInt(archive, pos, fh.payloadLen, fh.samples, channels, pcm, out, false);
            out += nSamples;
            pos += fh.payloadLen;
        }

        if (out != pcm.length) {
            // Defensive: header scan and decode disagreed (should not happen).
            short[] trim = new short[out];
            System.arraycopy(pcm, 0, trim, 0, out);
            pcm = trim;
        }
        return new DecodedArchive(sampleRate, channels, pcm);
    }

    public static final class DecodedArchive {
        public final int sampleRate;
        public final int channels;
        public final short[] pcm;

        public DecodedArchive(int sampleRate, int channels, short[] pcm) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.pcm = pcm;
        }

        public int frames() {
            return pcm.length / channels;
        }
    }

    static String buildXml(int sampleRate, X3AudioEncoder enc) {
        int[] c = enc.riceOrders();
        int[] t = enc.thresholds();
        // Trailing space keeps common layouts even-length after concat.
        return "<X3ARCH PROG=\"x3-java\" VERSION=\"2.0\" />"
                + "<CFG ID=\"0\" FTYPE=\"XML\" />"
                + "<CFG ID=\"1\" FTYPE=\"WAV\">"
                + "<FS UNIT=\"Hz\">" + sampleRate + "</FS>"
                + "<SUFFIX>wav</SUFFIX>"
                + "<CODEC TYPE=\"X3\" VERS=\"2\">"
                + "<BLKLEN>" + enc.blockLen() + "</BLKLEN>"
                + "<CODES N=\"4\">RICE" + c[0] + ",RICE" + c[1] + ",RICE" + c[2] + ",BFP</CODES>"
                + "<FILTER>DIFF</FILTER>"
                + "<NBITS>16</NBITS>"
                + "<T N=\"3\">" + t[0] + "," + t[1] + "," + t[2] + "</T>"
                + "</CODEC>"
                + "</CFG>"
                + " ";
    }

    private static int parseInt(Pattern p, String xml, int def) {
        Matcher m = p.matcher(xml);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return def;
    }

    private static int[] parseTriple(Pattern p, String xml, int[] def) {
        Matcher m = p.matcher(xml);
        if (m.find()) {
            return new int[] {
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))
            };
        }
        return def;
    }
}
