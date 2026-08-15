/// Pure-Java X3 audio codec: encoder, decoder, and `.wav`/`.x3a` conversion.
///
/// Framing ([X3FrameHeader], [Crc16]) and bitstream I/O ([BitstreamReader],
/// [BitstreamWriter]) are kept separate from the codec itself
/// ([X3AudioEncoder], [X3AudioDecoder]) so the bit-level and block-level logic can
/// each be tested and benchmarked independently. [ChunkPipeline] and
/// [DecodeScheduler] add parallel, concurrency-bounded decoding for random-access
/// hosts (see the `...sud` package); they take a flattened index table rather than
/// any container-specific type, keeping this package free of SUD framing.
package edu.cornell.raven.x3a;

import edu.cornell.raven.x3a.internal.*;
