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
./gradlew conversionBenchmark                         # wav<->x3a throughput, CSV output similar to x3-rust benchmark
```

`conversionBenchmark` runs the same six-file suite (`GI16`, `GI60`,
`GR48`, `LI192`, `NO96`, `PI240`) used throughout development to track
encode/decode MB/s and compression ratio.

## Usage

The public API lives in two packages: `edu.cornell.raven.x3a` (codec, readers,
file conversion) and `edu.cornell.raven.x3a.sud` (the `.SUD` container +
facade) — the only two packages exported by the JPMS module
(`src/main/java/module-info.java`). Everything in
`edu.cornell.raven.x3a.internal` is implementation detail and unreachable from
other modules.

### Decoding, container-agnostic

`X3Streams.open` sniffs the file's magic bytes and returns an `X3StreamingDecoder`
— `X3ArchiveStreamingDecoder` for a bare `.x3a` archive, `sud.SudStreamingDecoder` for a `.SUD`
container. Prefer this over naming a concrete decoder: SoundTrap file naming is
inconsistent, so the extension is not a reliable signal.

```java
import edu.cornell.raven.x3a.X3Streams;
import edu.cornell.raven.x3a.X3StreamingDecoder;

try (X3StreamingDecoder reader = X3Streams.open(Path.of("recording.sud"))) {
    int channels = reader.channels();           // also sampleRate(), bitDepth(), totalSamples()

    short[] window = new short[65536 * channels]; // allocate once, reuse per window
    long offset = 0;
    while (offset < reader.totalSamples()) {
        int got = reader.decodeSamplesInt(offset, 65536, window);
        if (got <= 0) break;
        // ...consume window[0, got * channels)...
        offset += got;
    }
}
```

Both readers index frame boundaries at open time, so reads are random-access:
only the frames a window touches get decoded. `decodeSamplesFloat` is also
available, normalizing to `[-1.0f, 1.0f]` on the fly. Both methods decode
directly into a caller-owned buffer — no allocation on the steady-state path.

Malformed framing or corrupt coded data surfaces as `X3FormatException`, an
`IOException` subclass, so callers can distinguish a bad file from an I/O
failure without catching runtime exceptions.

Construct `sud.SudStreamingDecoder` or `X3ArchiveStreamingDecoder` directly when you already know
the container and want its type-specific extras — `SudStreamingDecoder.metadata()`
(device tags, `xmlConfig()`) or `X3ArchiveStreamingDecoder.xmlConfig()`.

### Converting `.wav` ↔ `.x3a`

```java
import edu.cornell.raven.x3a.X3Files;

X3Files.wavToX3a(Path.of("in.wav"), Path.of("out.x3a"));
X3Files.x3aToWav(Path.of("out.x3a"), Path.of("roundtrip.wav"));

// Or decode an archive already in memory (whole file into one buffer — for large
// archives prefer X3ArchiveStreamingDecoder and read bounded windows instead):
X3Files.DecodedArchive dec = X3Files.decodeArchive(archiveBytes);
// dec.sampleRate(), dec.channels(), dec.pcm() (interleaved short[]), dec.xml() (embedded config)

// Metadata only, no PCM decode — works on both .x3a and .SUD:
X3Files.X3Header header = X3Files.readHeader(Path.of("recording.sud"));
```

### Tuning decode concurrency

`X3ArchiveStreamingDecoder`, `sud.SudStreamingDecoder`, `X3Streams.open`, and
`X3Files.decodeArchive` all accept a `DecodeOptions`:

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
tooling and the public `x3-rust` reference implementation.
Hard performance rules for anyone touching decode/parse/math paths are in
[`AGENTS.md`](AGENTS.md).

**Package layout:**

| Package | Role |
| --- | --- |
| `edu.cornell.raven.x3a` | Public API: `X3StreamingDecoder`, `X3Streams`, `X3ArchiveStreamingDecoder`, `X3Files`, `X3FrameDecoder`/`Encoder`, `DecodeOptions`, `X3FormatException` |
| `edu.cornell.raven.x3a.sud` | `.SUD` container: `SudFileMapper`, `FileMetadata`, `TelemetryCallback`, facade `SudStreamingDecoder` |
| `edu.cornell.raven.x3a.internal` | Not exported: `BitstreamReader`/`Writer`, `ChunkPipeline`, `ChunkIndex`, `ArchiveIndex`, framing/CRC helpers |

Dependency direction is one-way (`.sud` → core `x3a`), so the codec can be
used standalone on bare `.x3a` archives without any SUD-container concept.

**Key techniques:**

- **Zero-copy file access** — `.SUD` and `.x3a` files are mapped off-heap via
  `FileChannel` + `Arena`/`MemorySegment` (FFM API); metadata and chunk
  indexing walk the mapped memory directly rather than copying into `byte[]`.
- **In-memory chunk index** — a single pass builds a flat `long[]` index
  (`Sample_Offset`, `File_Byte_Offset`, `Chunk_Length`, `Frame_Timestamp` per
  chunk) enabling binary-search random seeking without on-disk sidecar files.
  `ChunkIndex` does this for `.SUD` records and `ArchiveIndex` for `.x3a`
  frames, sharing one layout so both feed the same `ChunkPipeline`.
- **Allocation-free decode loops** — no heap allocation, boxing, or
  multi-dimensional arrays inside decode/math loops (flat interleaved
  `short[]`/`int[]` throughout); loops are kept branch-free and countable so
  the JIT can auto-vectorize them (see `AGENTS.md`'s four hard rules).
- **Virtual-thread parallel decode** — independent chunks/frames (each holds
  its own filter state) decode concurrently via
  `Executors.newVirtualThreadPerTaskExecutor()`, gated by local + optional
  process-wide `Semaphore` limits (`ChunkPipeline` for windowed reads of either
  container, the parallel path in `X3Files.decodeArchive` for whole-file
  archive decode).


## Project layout

```
src/main/java   library source (the two exported packages above)
src/test/java   unit + fixture-integration tests
src/jmh/java    JMH microbenchmarks + the wav<->x3a benchmark
src/tools/java  JavaFX verification app (not part of the library, not exported)
test/           wav/x3a/flac fixtures used by benchmarks
x3-rust/        reference Rust implementation, used for cross-validation only
```

## Trademark notice

SoundTrap (TM) is a trademark of Ocean Instruments New Zealand. This project
is an independent, reverse-engineered implementation and is not affiliated
with, funded by, or associated with Ocean Instruments or Spotify AB.
