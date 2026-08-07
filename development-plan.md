# High-Performance X3 (.SUD) Audio Decoder Development Plan

## 1. Executive Summary
The target is a high-performance, native **X3 Audio Decoder** written in modern **Java (JDK 25+)**. The engine must decompress Ocean Instruments' SoundTrap `.SUD` (SoundTrap Underwater Data) files and stream standard linear PCM samples directly to a native `libsndfile` FLAC pipeline via the Foreign Function & Memory (FFM) API. 

### Key Performance Goals
* **Zero GC Overhead:** Eliminate heap allocation during the core decoding loop.
* **Maximized Parallelism:** Leverage hardware concurrency to out-pace single-threaded file processing.
* **JIT-Driven Acceleration:** Optimize data structures to allow the JVM HotSpot compiler to auto-vectorize heavy mathematical loops natively.
* **Low Memory Footprint:** Map multi-gigabyte data files virtually without exhausting host RAM.

### Target Read Profiles
* **Sequential Stream:** Read the entire file continuously from start to finish.
* **Bounded Stream:** Read a sequential window of a specific length from a given start point.
* **Random Access (Seeking):** Instantly seek to specific sample indexes or timestamps without generating disk-heavy `.sudx` index files.

### Build tooling
* The project will use Gradle 9.3.1 (with wrapper) for build and dependencies. 
* It should use gradle plugins for jlink so we can build executables.

---

## 2. Technical Stack & JDK 25 Features

| Feature | Architecture Role | Performance Benefit |
| :--- | :--- | :--- |
| **FFM API (`MemorySegment`)** | Maps raw file blocks off-heap using `Arena.ofConfined()`. | **Zero Data Copying**: Eliminates heap allocation for large buffer structures. |
| **Virtual Threads (Loom)** | Schedules chunk decompression concurrently via `StructuredTaskScope`. | Maximize multi-core execution with zero thread scheduling overhead. |
| **JIT Auto-Vectorization** | Structuring loops cleanly to trigger native SIMD compilation (AVX2/Neon). | Cross-platform, hardware-safe mathematical parallelization without unstable experimental APIs. |

---

## 3. Data Layout & Chunk Strategy
A `.SUD` file is a sequential container of framed binary chunks. The decoder must parse individual block headers to route data blocks properly:


+------------------------------------------------------------+
|                       FILE HEADER                          |
|  - System Calibration, Sample Rate, Bit Depth, Channels    |
+------------------------------------------------------------+
|                       DATA CHUNKS                          |
|  +------------------------------------------------------+  |
|  | CHUNK HEADER: Type Flag, Payload Length, Timestamp   |  |
|  +------------------------------------------------------+  |
|  | AUDIO PAYLOAD: X3 Variable-Bit-Length Stream Blocks  |  |
|  +------------------------------------------------------+  |
|  | METADATA PAYLOAD: Device Sensor Logs / Raw XML Config|  |
|  +------------------------------------------------------+  |
+------------------------------------------------------------+

### Routing Rules
* **Acoustic Audio Chunks:** Decode the bitstream to retrieve raw PCM values.
* **Non-Acoustic Chunks:** Route sensor data (temperature, pressure, voltage) directly to a user-provided telemetry callback.
* **Metadata Chunks:** Parse raw XML records directly into text strings for file headers.

---

## 4. Phase-by-Phase Implementation Blueprint

### Phase 1: Zero-Copy File Mapping & Metadata Ingestion
* **Objective:** Parse file syntax without copying data buffers onto the JVM heap.
* **Implementation:** 
  * Open the `.SUD` file using standard channel APIs and map it into an off-heap `MemorySegment`.
  * Define structured `VarHandle` layouts to fetch global file configurations (sample rate, channel counts).
  * Extract device tracking tags and the initial configuration block.

### Phase 2: In-Memory Index Table Generator (Fast Seeking)
* **Objective:** Allow rapid random access without building `.sudx` sidecar files on disk.
* **Implementation:** 
  * Build a single-pass, ultra-fast initialization thread that skips across chunk payloads using chunk size headers.
  * Cache layout data in a flattened primitive array: `long[] indexTable`.
  * For every audio chunk, index exactly 4 elements: `[Sample_Offset, File_Byte_Offset, Chunk_Length, Frame_Timestamp]`.
  * **Seeking Mechanism:** Use binary searches on the `indexTable` to find target sample offsets instantly.

### Phase 3: JIT-Friendly Audio & Bitstream Unpacking
* **Objective:** Structure predictive coding loops to guarantee HotSpot auto-vectorization across dual primitive types.
* **Implementation:** 
  * Track variable-bit streaming pointers inside primitive `int` registers using bitwise operators (`<<`, `>>`, `&`).
  * **Flatten all multi-channel audio tracks** into a single pre-allocated, flat 1D array (`int[]` or `short[]`) to maximize hardware cache locality.
  * **Expose Dual Exporters:** 
    1. Provide an integer export path that passes the raw decompressed 1D array reference directly to the FFM layer.
    2. Provide a floating-point export path via a zero-allocation streaming loop. This method populates a caller-owned, pre-allocated `float[]` array, normalizing the 16-bit integer space down to a floating-point range between `[-1.0f, 1.0f]` by multiplying each sample by `(1.0f / 32768.0f)`.
  * Ensure the mathematical normalization loop is completely free of branching statements (`if/else`), object allocations, or sub-method wrappers to guarantee JIT compiler auto-vectorization down to native CPU SIMD lanes.

### Phase 4: Threaded Parallel Processing Chunk Pipeline
* **Objective:** Scale decoder processing throughput linearly across available CPU threads.
* **Implementation:** 
  * Divide the master file-mapped `MemorySegment` into minor, isolated block slices based on the index table.
  * Distribute stateless audio decoding chunks across virtual worker threads using `StructuredTaskScope` to balance workloads simultaneously.
  * Limit active virtual threads using a carrier-thread throttle to avoid over-saturating underlying native processing blocks.

### Phase 5: Off-Heap FFM Streaming to `libsndfile`
* **Objective:** Deliver decoded data to your native FLAC encoder without memory-copy delays.
* **Implementation:** 
  * Feed output memory segment addresses directly to native C-bindings using FFM calls like `sf_writef_int`.
  * Sync workflows so that thread group `N+1` decodes in memory while thread group `N` is actively being committed to disk via `libsndfile`.

---

## 5. Explicit Prompting Guardrails for Coding Agents

When assigning coding tasks to LLMs or autonomous agents, paste these strict rules alongside the phase instructions:

> ### Rule 1: Absolute Heap-Allocation Ban
> *"Do not allocate objects, arrays, or wrappers (such as `Integer`, `Byte`, or `ByteBuffer`) inside any decoding, parsing, or math loops. Use pre-allocated, flat primitive arrays passed as reference variables, or read directly from a pre-allocated off-heap `MemorySegment` using static `VarHandle` lookups."*

> ### Rule 2: Mandated Multi-Channel Flattening
> *"Multi-dimensional structures like `int[][]` or `short[][]` are strictly prohibited. You must flatten all target dimensions into a standard 1D array (`int[]` or `short[]`). Track and traverse offsets using explicit inline multiplication arithmetic to preserve memory locality."*

> ### Rule 3: Native Thread Optimization Guardrails
> *"Do not use synchronized blocks, heavy thread locks, or traditional heavy-thread allocations inside chunk execution paths. Ensure all processing logic inside chunks is completely stateless, relying exclusively on local variables and segment slices to leverage JDK 25 virtual worker threads."*

> ### Rule 4: Loop Purity for Auto-Vectorization
> *"The core mathematical predictor loops must be strictly countable standard `for` loops. Do not include object instantiation, method invocations, try-catch blocks, or conditional branching (`if`/`else`) inside the innermost audio loops. All memory read and write targets must access contiguous segments of the 1D arrays to ensure the JIT compiler successfully maps instructions to native CPU SIMD registers."*
