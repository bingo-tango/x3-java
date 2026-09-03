package edu.cornell.raven.x3a;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// Whole-file `.wav` ↔ `.x3a` conversion, for tests and upstream tooling.
///
/// Pure Java, no libsndfile. A thin shell over the bulk codec — [X3BulkEncoder] and
/// [X3BulkDecoder] do the work, and this class only adds WAV I/O — so use those directly when
/// the PCM is already in memory, or [X3Streams#open] when the file is too large to decode into
/// one buffer.
public final class X3Files {

    private X3Files() {
    }

    /// Converts a 16-bit PCM WAV file to an X3 archive (`.x3a`). Overwrites `x3aPath` if it exists.
    public static void wavToX3a(Path wavPath, Path x3aPath) throws IOException {
        WavPcm.WavData wav = WavPcm.read(wavPath);
        byte[] archive = X3BulkEncoder.encode(wav.samples, wav.frames, wav.channels, wav.sampleRate);
        Files.write(x3aPath, archive);
    }

    /// Converts an X3 archive (`.x3a`) to a 16-bit PCM WAV file. Overwrites `wavPath` if it exists.
    public static void x3aToWav(Path x3aPath, Path wavPath) throws IOException {
        X3BulkDecoder.DecodedArchive dec = X3BulkDecoder.decode(x3aPath);
        WavPcm.write(wavPath, dec.sampleRate(), dec.channels(), dec.pcm());
    }
}
