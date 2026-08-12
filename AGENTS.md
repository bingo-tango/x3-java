# AGENTS.md — coding guardrails for x3-java

This repo is the **X3 / `.SUD` decode library** (`edu.cornell.raven.core.audio.x3a`).  

## General guidelines

- Use Markdown format for all Javadoc.

## Non-negotiables

* **No `libsndfile` (or other native encode/export) implementation in this repo.** Upstream applications own FFM writers and FLAC/WAV paths. Document integration only; do not add writer types under `src/`.
* **SUD container types live under `edu.cornell.raven.core.audio.x3a.sud` only.** Codec/pipeline types stay in `edu.cornell.raven.core.audio.x3a`. Dependency direction: `.sud` → core `x3a`, never the reverse.

## Performance rules (hard)

When implementing or changing decode, parse, index, or math paths, obey all four rules.

### Rule 1: Absolute heap-allocation ban

Do not allocate objects, arrays, or wrappers (such as `Integer`, `Byte`, or `ByteBuffer`) inside any decoding, parsing, or math loops. Use pre-allocated, flat primitive arrays passed as reference variables, or read directly from a pre-allocated off-heap `MemorySegment` using static `VarHandle` lookups.

### Rule 2: Mandated multi-channel flattening

Multi-dimensional structures like `int[][]` or `short[][]` are strictly prohibited. Flatten all target dimensions into a standard 1D array (`int[]` or `short[]`). Track and traverse offsets using explicit inline multiplication arithmetic to preserve memory locality.

### Rule 3: Stateless chunk work for virtual threads

Do not use synchronized blocks, heavy thread locks, or traditional heavy-thread allocations inside chunk execution paths. Ensure all processing logic inside chunks is completely stateless, relying exclusively on local variables and segment slices to leverage JDK 25 virtual worker threads.

### Rule 4: Loop purity for auto-vectorization

The core mathematical predictor loops must be strictly countable standard `for` loops. Do not include object instantiation, method invocations, try-catch blocks, or conditional branching (`if`/`else`) inside the innermost audio loops. All memory read and write targets must access contiguous segments of the 1D arrays to ensure the JIT compiler successfully maps instructions to native CPU SIMD registers.
