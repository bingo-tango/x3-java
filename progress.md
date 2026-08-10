# Development Plan Progress

## Phase 1 — Zero-Copy File Mapping & Metadata Ingestion: DONE

- `SudFileMapper` maps `.SUD` files via `FileChannel` + `Arena`/`MemorySegment` (zero-copy), and
  `parseHeader()` walks the **entire** file record-by-record using a `VarHandle`-backed 20-byte
  record header layout, extracting `FileMetadata` (sample rate, channels, bit depth, device ID,
  full metadata XML).
- `X3Decoder` facade fixed to actually delegate to `SudFileMapper` (previously duplicated/ignored
  it, breaking the `FAC --> MAP` architecture edge).
- No vendor spec was available; the record framing and text encoding below were reverse-engineered
  from `src/test/resources/7867.230815161432.sud` and cross-validated against a real
  OceanInstruments software export (`7867.230815161432.log.xml`, also added as a test fixture) —
  `SudFileMapperTest.xmlConfigMatchesOceanInstrumentsReferenceExport()` confirms our decoded
  metadata matches that reference byte-for-byte (after normalizing whitespace/padding).

### Confirmed binary format facts (empirical, no vendor spec)

- **Every record in the file** (metadata or binary audio chunk) shares one 20-byte header:
  `sync(2)=0x52,0xA9 | reserved0(2)=0x00,0x00 | payloadLength(2, LE) | recordType(2, LE) | sessionId(4) | sequence(2) | recordTag(6)`,
  followed by `payloadLength` bytes of payload.
- `recordType == 1` → metadata/event record. Payload is ASCII XML-like text
  (`<EVENT>...`, `<CFG ...>...`) stored with **every adjacent byte pair swapped**
  (e.g. `"<EVENT>"` on disk as `"E<EVTN..."`). Records are padded to an even byte
  count with a trailing NUL before the swap — stripped during decode.
- `recordType != 1` → binary acoustic-audio chunk. **Not yet understood**: the field
  is not a small type enum — in the fixture it ranges ~3232–6336 across ~40,673
  chunks. Current Phase 1 code treats "not 1" as "skip via payloadLength, don't
  decode," which is sufficient for metadata extraction but is **not** a real
  chunk-type classification.
- Metadata records appear in **two runs**: a leading run (device tags + the full
  `<CFG>` decimation/codec chain) starting ~byte 30, and a **trailing** run after
  the last audio chunk (an end-of-session `<EVENT>`), just before EOF padding
  (observed as `0xFF` bytes to end of file). `parseHeader()` captures both.
- Real config chain recovered from the fixture (`<CFG ID="1">` → `<CFG ID="4">`):
  raw source PROC=AUDIO, FS=192000 Hz, NCHS=1, NBITS=16 → DECM decimation DF=4 →
  X3V2-coded bitstream (`BLKLEN=16, FILTER=diff, NBITS=16`, RICE0/RICE1/RICE3/BFP
  codes) → decoded/WAV-equivalent config FS=48000, NBITS=16, NCHS=1 (192000/4=48000,
  confirms the decimation math). `FileMetadata` reports the **decoded** 48000/1/16
  config (what downstream WAV writers need), not the raw 192000 Hz source rate.
- Device tag recovered via `<EVENT><HARDWARE_ID> ST600 </HARDWARE_ID></EVENT>`.

## Phase 2 — In-Memory Index Table Generator: DONE

- Both open questions from the original stub were resolved empirically against
  the real fixture (`src/test/resources/7867.230815161432.sud`) before writing
  any implementation:
  - **The header field previously assumed to be `recordType` is not a type
    enum at all — for audio chunks, it *is* that chunk's decoded sample
    count** (always a multiple of `BLKLEN=16`). Confirmed by summing the field
    across all 40,673 non-metadata records in the fixture: the sum is exactly
    **172,813,552**, matching OceanInstruments' own `SampleCount="172813552"`
    attribute in the reference export (`7867.230815161432.log.xml`) byte for
    byte. The value `1` for metadata records is simply a sentinel that never
    collides with a real (multiple-of-16, ≥16) sample count, so "not
    metadata" (`!= 1`) remains sufficient for chunk classification — no
    `ChunkType` enum was needed, and the speculative one from the original
    stub was deleted along with its test.
  - **`Frame_Timestamp`** is computed, not read: cumulative decoded sample
    count before the chunk, converted to nanoseconds via the decoded sample
    rate (`FileMetadata.sampleRate()`, 48 kHz in the fixture) — no header
    field is a literal timestamp.
- The 20-byte record header layout/VarHandles are now factored out of
  `SudFileMapper` into a shared package-private `RecordHeader` helper (layout,
  `hasSyncAt`/`payloadLength`/`sampleCountOrRecordType`/`isMetadata`, and
  `findFirstSync`), used by both `SudFileMapper.parseHeader()` and
  `ChunkIndex.build()` — avoiding the drift the original stub's independent
  13-byte-header reimplementation would have caused.
- `ChunkIndex.build(MemorySegment, int sampleRate)` walks every record via
  `RecordHeader`, skips metadata (including the trailing end-of-session
  record), and indexes everything else using the *real* per-chunk sample
  count for `Sample_Offset`/`Frame_Timestamp` (not `payloadLength`, which is
  the compressed byte length and only appropriate for `Chunk_Length`).
  `findChunkBySample()` is a real upper-bound binary search;
  `totalSamples()` gives the bound for range checks.
- `X3Decoder` now holds a real `ChunkIndex` (built in the constructor from
  `mapper.mappedFile()` + `metadata.sampleRate()`) instead of the dummy
  13-byte-header stub with its own duplicate `indexTable`/`totalChunks`
  fields — same "stop duplicating, delegate" fix pattern as Phase 1's
  `X3Decoder --> SudFileMapper` convergence.
- `ChunkIndexTest` covers metadata-skipping, cumulative sample offset/
  timestamp correctness, `totalSamples()`, and `findChunkBySample` boundary
  cases against synthetic 20-byte-header fixtures, plus a real-fixture
  integration test asserting `chunkCount() == 40673` and
  `totalSamples() == 172_813_552` against the actual 83 MB `.sud` file
  (mirrors Phase 1's real-fixture cross-check in `SudFileMapperTest`).
- **Still unexplored, not needed for indexing:** the exact meaning of
  `sequence` (resets at what look like session/segment boundaries) and the
  6-byte `recordTag`. Neither was needed to build a correct index; may matter
  for later phases (e.g. detecting dropped/out-of-order chunks) but is
  deferred rather than blocking.

## Phases 3–6

Not started. No changes to plan/scope.
