# x3-java

A pure-Java decoder (and encoder) for the **X3** lossless acoustic codec. X3 is the lossless compression algorithm
used by Ocean Instruments **SoundTrap** recorders in their `.SUD` container
format. 

The X3 algorithm was originally described in this paper: 

Mark Johnson, Jim Partan, Tom Hurst; Low complexity lossless compression of underwater sound recordings. 
J. Acoust. Soc. Am. 1 March 2013; 133 (3): 1387–1398. 
https://doi.org/10.1121/1.4776206

This implementation targets JDK 25, built around the Foreign Function & Memory API,
virtual threads, and allocation-free hot paths — no JNI, no native libraries.


## Requirements

- JDK 25 (the build pins a Gradle toolchain to `25`, so any installed JDK works —
  Gradle will provision 25 itself if needed).
- Git LFS — `test/*.sud` and `test/*.wav` fixtures are tracked via
  [`.gitattributes`](.gitattributes); run `git lfs pull` after cloning if those
  files look like small pointer stubs.
- No other native dependencies. The Gradle wrapper (`gradlew` / `gradlew.bat`)
  is checked in, so no local Gradle install is required.

## Build

```bash
./gradlew build   # compile + run the full test suite
./gradlew test    # tests only
```

`./gradlew test` exercises the codec against real fixtures: a full 83 MB
SoundTrap `.SUD` capture, round-trip `.wav` ↔ `.x3a` conversion (lossless
bit-for-bit), and parallel-vs-sequential decode equivalence.

## Running

This repo is a **library**, not a CLI application — there's no `./gradlew run`.
Two runnable entry points exist for manual testing and performance work:

### GUI verification app

```bash
./gradlew runTestApp
```

Opens a small JavaFX window with a single drop zone. Drag in one `.x3a` file
and it converts on a background thread, same as before. Drag in one or more
`.SUD` files, or a folder (scanned recursively for `.SUD` files), and they
decode in parallel across virtual threads, with an overall progress bar and a
per-file status list. Every output gets a `.wav` (and a `.xml` metadata
sidecar, when metadata is available) next to its source file.
This is test scaffolding for manually verifying the decoder end-to-end — it's
not part of the library API and isn't exported from the Java module.

### Benchmarks

```bash
./gradlew jmh                                        # all JMH microbenchmarks
./gradlew jmh -Pjmh.includes=.*BitstreamReader.*      # filter to one benchmark
./gradlew conversionBenchmark                         # paper-suite wav<->x3a throughput, CSV output
```

`conversionBenchmark` runs the same six-file "paper suite" (`GI16`, `GI60`,
`GR48`, `LI192`, `NO96`, `PI240`) used throughout development to track
encode/decode MB/s and compression ratio.

## Usage

The public API lives in two packages: `edu.cornell.raven.core.audio.x3a`
(codec/pipeline) and `edu.cornell.raven.core.audio.x3a.sud` (the `.SUD`
container + facade) — the only two packages exported by the JPMS module
(`src/main/java/module-info.java`).

### Decoding a `.SUD` file

```java
import sud.edu.cornell.raven.x3a.X3Decoder;
import sud.edu.cornell.raven.x3a.FileMetadata;

try (X3Decoder decoder = new X3Decoder(Path.of("recording.sud"))) {
    FileMetadata meta = decoder.metadata();     // sampleRate(), channels(), bitDepth(), xmlConfig()
    long totalSamples = decoder.chunkIndex().totalSamples();

    short[] window = new short[65536 * meta.channels()]; // allocate once, reuse per window
    long offset = 0;
    while (offset < totalSamples) {
        int got = decoder.decodeSamplesInt(offset, 65536, window);
        if (got <= 0) break;
        // ...consume window[0, got * channels)...
        offset += got;
    }
}
```

`decodeSamplesFloat` is also available, normalizing to `[-1.0f, 1.0f]` on the
fly. Both methods decode directly into a caller-owned buffer — no allocation
on the steady-state path.

### Converting `.wav` ↔ `.x3a`

```java
import edu.cornell.raven.x3a.X3Files;

X3Files.wavToX3a(Path.of("in.wav"), Path.of("out.x3a"));
X3Files.x3aToWav(Path.of("out.x3a"), Path.of("roundtrip.wav"));

// Or decode an archive already in memory:
X3Files.DecodedArchive dec = X3Files.decodeArchive(archiveBytes);
// dec.sampleRate, dec.channels, dec.pcm (interleaved short[]), dec.xml (embedded config)
```

### Tuning decode concurrency

Both `X3Decoder` and `X3Files.decodeArchive` accept a `DecodeOptions`:

```java
DecodeOptions opts = DecodeOptions.defaults().withMaxConcurrency(2);
```

Defaults cap per-decoder concurrency at `min(4, cores/2)` with a process-wide
shared limiter on by default (so many decoders in one process don't
oversubscribe CPU) — override via `-Dx3a.decode.maxConcurrency=N` /
`-Dx3a.decode.sharedLimiter=false`, or programmatically.

## Design

The codec was reverse-engineered against a real SoundTrap fixture (no vendor
spec was available) and cross-validated against OceanInstruments' own export
tooling and the public `x3-rust` reference implementation (vendored locally
under `x3-rust/` for comparison, not part of this build).
Hard performance rules for anyone touching decode/parse/math paths are in
[`AGENTS.md`](AGENTS.md).

**Package layout:**

| Package | Role |
| --- | --- |
| `edu.cornell.raven.core.audio.x3a` | Codec + pipeline: `BitstreamReader`/`Writer`, `X3AudioDecoder`/`Encoder`, `ChunkPipeline`, `X3Files` (wav↔x3a conversion) |
| `edu.cornell.raven.core.audio.x3a.sud` | `.SUD` container: `SudFileMapper`, `ChunkIndex`, `FileMetadata`, `TelemetryCallback`, facade `X3Decoder` |

Dependency direction is one-way (`.sud` → core `x3a`), so the codec can be
used standalone on bare `.x3a` archives without any SUD-container concept.

**Key techniques:**

- **Zero-copy file access** — `.SUD` files are mapped off-heap via
  `FileChannel` + `Arena`/`MemorySegment` (FFM API); metadata and chunk
  indexing walk the mapped memory directly rather than copying into `byte[]`.
- **In-memory chunk index** — a single pass builds a flat `long[]` index
  (`Sample_Offset`, `File_Byte_Offset`, `Chunk_Length`, `Frame_Timestamp` per
  chunk) enabling binary-search random seeking without on-disk sidecar files.
- **Allocation-free decode loops** — no heap allocation, boxing, or
  multi-dimensional arrays inside decode/math loops (flat interleaved
  `short[]`/`int[]` throughout); loops are kept branch-free and countable so
  the JIT can auto-vectorize them (see `AGENTS.md`'s four hard rules).
- **Virtual-thread parallel decode** — independent chunks/frames (each holds
  its own filter state) decode concurrently via
  `Executors.newVirtualThreadPerTaskExecutor()`, gated by local + optional
  process-wide `Semaphore` limits (`ChunkPipeline` for `.SUD`, the parallel
  path in `X3Files.decodeArchive` for bare `.x3a`).


## Project layout

```
src/main/java   library source (the two exported packages above)
src/test/java   unit + fixture-integration tests
src/jmh/java    JMH microbenchmarks + the wav<->x3a paper-suite benchmark
src/tools/java  JavaFX verification app (not part of the library, not exported)
test/           paper-suite wav/x3a/flac fixtures used by benchmarks
x3-rust/        reference Rust implementation, used for cross-validation only
```

## Trademark notice

SoundTrap (TM) is a trademark of Ocean Instruments New Zealand. This project
is an independent, reverse-engineered implementation and is not affiliated
with, funded by, or associated with Ocean Instruments or Spotify AB.
