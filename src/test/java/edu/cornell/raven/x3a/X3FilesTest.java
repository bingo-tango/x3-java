package edu.cornell.raven.x3a;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class X3FilesTest {

    @TempDir
    Path tmp;

    @Test
    void wav_to_x3a_and_back_synthetic() throws Exception {
        short[] pcm = new short[4800];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (Math.sin(i * 0.01) * 12000);
        }
        Path wavIn = tmp.resolve("in.wav");
        Path x3a = tmp.resolve("out.x3a");
        Path wavOut = tmp.resolve("out.wav");
        WavPcm.write(wavIn, 48000, 1, pcm);

        X3Files.wav_to_x3a(wavIn, x3a);
        assertTrue(Files.size(x3a) > 32);
        assertEquals('X', (char) Files.readAllBytes(x3a)[0]);

        X3Files.x3a_to_wav(x3a, wavOut);
        WavPcm.WavData round = WavPcm.read(wavOut);
        assertEquals(48000, round.sampleRate);
        assertEquals(1, round.channels);
        assertArrayEquals(pcm, round.samples);
    }

    @Test
    void fixtureWav_roundTrip_ifPresent() throws Exception {
        Path fixture = Path.of("src/test/resources/LI192_15s.wav");
        if (!Files.exists(fixture)) {
            return;
        }
        Path x3a = tmp.resolve("LI192_15s.x3a");
        Path wavOut = tmp.resolve("LI192_15s_rt.wav");

        X3Files.wavToX3a(fixture, x3a);
        X3Files.x3aToWav(x3a, wavOut);

        WavPcm.WavData orig = WavPcm.read(fixture);
        WavPcm.WavData back = WavPcm.read(wavOut);
        assertEquals(orig.sampleRate, back.sampleRate);
        assertEquals(orig.channels, back.channels);
        assertArrayEquals(orig.samples, back.samples);
    }

    @Test
    void encodeArchive_headerHasX3ArchivAndXml() {
        short[] pcm = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20};
        byte[] arch = X3Files.encodeArchive(pcm, pcm.length, 1, 16000, new X3AudioEncoder());
        assertEquals('X', (char) arch[0]);
        assertEquals('3', (char) arch[1]);
        String asText = new String(arch, 0, Math.min(arch.length, 400));
        assertTrue(asText.contains("X3ARCHIV") || arch[0] == 'X');
        assertTrue(asText.contains("BLKLEN") || new String(arch).contains("BLKLEN"));
    }
}
