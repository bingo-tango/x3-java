package edu.cornell.raven.x3a;

import edu.cornell.raven.x3a.internal.BitstreamWriter;
import edu.cornell.raven.x3a.internal.Crc16;
import edu.cornell.raven.x3a.internal.X3FrameHeader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/// Whole-archive encode: interleaved PCM in, a complete `.x3a` byte image out.
///
/// The encode-side counterpart to [X3BulkDecoder], and the layer above [X3FrameEncoder] —
/// this class owns the container (the `X3ARCHIV` id, the XML config frame, and one data frame
/// per `samplesPerFrame` block); the frame encoder owns the bitstream inside each payload.
///
/// Output layout matches the public X3 archive format (x3-rust / `x3new.m`), so archives are
/// interchangeable with the reference tools.
public final class X3BulkEncoder {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private X3BulkEncoder() {
    }

    /// Builds a complete `.x3a` byte image from interleaved PCM, using [X3FrameEncoder]'s
    /// defaults.
    public static byte[] encode(short[] pcm, int frames, int channels, int sampleRate) {
        return encode(pcm, frames, channels, sampleRate, new X3FrameEncoder());
    }

    /// Builds a complete `.x3a` byte image from interleaved PCM.
    ///
    /// The encoder's block length, rice orders, and thresholds are written into the archive's
    /// XML config frame, so [X3BulkDecoder] recovers them without out-of-band agreement.
    ///
    /// @param pcm        interleaved samples, at least `frames * channels` long
    /// @param frames     samples per channel to encode
    /// @param channels   channel count
    /// @param sampleRate sample rate in Hz, recorded as the config frame's `<FS>`
    /// @param encoder    frame codec supplying the coding parameters
    public static byte[] encode(short[] pcm, int frames, int channels, int sampleRate,
                                X3FrameEncoder encoder) {
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
                long timeNanos = offFrames * NANOS_PER_SECOND / sampleRate;
                out.write(new X3FrameHeader(1, channels, n, payload.length, timeNanos, bp.crc()).encode());
                out.write(payload);
                offFrames += n;
            }
        } catch (IOException e) {
            // ByteArrayOutputStream does not throw
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /// The archive's XML config frame, describing the coding parameters `encoder` will use so a
    /// decoder can reconstruct them.
    static String buildXml(int sampleRate, X3FrameEncoder enc) {
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
}
