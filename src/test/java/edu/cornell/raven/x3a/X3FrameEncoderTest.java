package edu.cornell.raven.x3a;

import edu.cornell.raven.x3a.internal.BitstreamWriter;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class X3FrameEncoderTest {

    @Test
    void residualToRiceIndex_inverseOfDecoderTable() {
        assertEquals(0, X3FrameEncoder.residualToRiceIndex(0));
        assertEquals(1, X3FrameEncoder.residualToRiceIndex(-1));
        assertEquals(2, X3FrameEncoder.residualToRiceIndex(1));
        assertEquals(3, X3FrameEncoder.residualToRiceIndex(-2));
        assertEquals(4, X3FrameEncoder.residualToRiceIndex(2));
    }

    @Test
    void encodeDecode_frameLossless_quietSignal() {
        short[] pcm = new short[64];
        short v = 100;
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = v;
            v += (short) ((i % 5) - 2);
        }
        roundTrip(pcm, 1, 20);
    }

    @Test
    void encodeDecode_frameLossless_largerJumps() {
        short[] pcm = new short[100];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (Math.sin(i * 0.2) * 8000);
        }
        roundTrip(pcm, 1, 20);
    }

    @Test
    void encodeDecode_stereoInterleaved() {
        short[] pcm = new short[80]; // 40 frames * 2 ch
        for (int i = 0; i < 40; i++) {
            pcm[i * 2] = (short) (i * 3);
            pcm[i * 2 + 1] = (short) (-i * 2);
        }
        roundTrip(pcm, 2, 16);
    }

    @Test
    void encodeDecode_bfpPath_highEnergy() {
        short[] pcm = new short[40];
        pcm[0] = 0;
        for (int i = 1; i < pcm.length; i++) {
            // diffs of ~100 force BFP (threshold[2]=20)
            pcm[i] = (short) (pcm[i - 1] + ((i & 1) == 0 ? 100 : -90));
        }
        roundTrip(pcm, 1, 20);
    }

    private static void roundTrip(short[] pcm, int channels, int blockLen) {
        int frames = pcm.length / channels;
        X3FrameEncoder enc = new X3FrameEncoder(blockLen, 500, new int[] {0, 1, 3}, new int[] {3, 8, 20});
        BitstreamWriter bp = enc.encodeFrame(pcm, 0, frames, channels);
        byte[] payload = bp.toByteArray();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(payload.length);
            for (int i = 0; i < payload.length; i++) {
                seg.set(ValueLayout.JAVA_BYTE, i, payload[i]);
            }
            short[] out = new short[pcm.length];
            X3FrameDecoder dec = new X3FrameDecoder(blockLen, new int[] {0, 1, 3});
            int n = dec.decodeChunkInt(seg, frames, channels, out, 0, false);
            assertEquals(frames, n);
            assertArrayEquals(pcm, out);
        }
    }
}
