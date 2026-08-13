/// SoundTrap `.SUD` container support: zero-copy file mapping, chunk indexing, and
/// random-access decode built on the core X3 codec.
///
/// [SudFileMapper] maps a file and recovers its device/audio configuration
/// ([FileMetadata]); [edu.cornell.raven.x3a.internal.ChunkIndex] then walks the same record framing
/// ([RecordHeader]) to build a seek table. [X3Decoder] ties both together with
/// [edu.cornell.raven.x3a.internal.ChunkPipeline] into a single per-file handle
/// for windowed sample reads. The container's record/text framing was empirically
/// reverse-engineered (no vendor spec was available) — see [SudFileMapper]'s class
/// doc for the details.
package edu.cornell.raven.x3a.sud;

import edu.cornell.raven.x3a.internal.RecordHeader;
