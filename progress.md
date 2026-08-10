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

## Phase 2 — In-Memory Index Table Generator: NOT STARTED

`X3Decoder.buildInMemoryIndexTable()` is still the original stub and has **incorrect
assumptions** that need to be replaced, not incrementally patched:

- It assumes a **13-byte** chunk header (`type byte + 4-byte length + 8-byte
  timestamp`) and skips a fixed 128-byte file header first. Neither matches
  reality — the real header is **20 bytes** and is uniform across metadata *and*
  audio records (see `SudFileMapper.RECORD_HEADER_LAYOUT`). That layout/VarHandle
  work should be reused (or factored into a shared internal helper) rather than
  re-derived, to avoid the two classes drifting again like `X3Decoder` vs
  `SudFileMapper` did before the Phase 1 fix.
- The index walk needs to mirror `parseHeader()`'s "walk every record, skip the
  ones you don't care about via `payloadLength`" pattern — i.e. skip `recordType
  == 1` metadata records (including the trailing end-of-session one) while
  indexing everything else as audio chunks, rather than stopping at the first
  metadata/non-audio record encountered.
- **Open question before implementation:** what the `recordType` field actually
  encodes for audio chunks (it's not a simple enum — see above). Needs a short
  empirical pass (similar to how Phase 1's format was reverse-engineered) before
  designing `ChunkType`. It's possible chunk classification doesn't need this
  field at all — "not metadata" may be sufficient, same as Phase 1 treats it.
- **Open question:** where `Frame_Timestamp` (4th index element) comes from. The
  header's `sessionId` is opaque/constant, `sequence` is opaque and monotonic-ish,
  and `recordTag` (6 bytes) is unexplored. None was confirmed as a literal
  timestamp in Phase 1's investigation — it may need to be computed from
  cumulative sample count × sample period rather than read directly from a field.
- Sample-offset math should account for the decimation chain above: audio chunks
  are already encoded at the **decimated** 48 kHz/16-bit/1-channel rate (per `<CFG
  ID="3"/ID="4">`), not the raw 192 kHz source rate — worth double-checking
  `BLKLEN=16` against however `Chunk_Length` ends up being interpreted (bytes vs.
  samples).
- The real fixture (`7867.230815161432.sud`, ~83 MB, ~40,673 non-metadata records)
  is a good stress fixture for the index table and binary-search seeking — much
  larger scale than the existing all-zero synthetic test files.

## Phases 3–6

Not started. No changes to plan/scope.
