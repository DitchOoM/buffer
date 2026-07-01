package com.ditchoom.buffer.flow

/**
 * A multiplexed connection over **raw byte streams** — no codec fixed at the mux level.
 *
 * This is the heterogeneous-mux primitive. [StreamMux] fixes one codec across every stream,
 * which fits transports where all streams speak the same wire format. Some protocols instead
 * make each stream self-describing: HTTP/3 (RFC 9114 §6.2) prefixes each peer-initiated
 * unidirectional stream with a stream-type varint (control, QPACK encoder/decoder, push, …),
 * and each type is a *different* codec. The codec cannot be chosen until the prefix has been
 * read off the accepted stream — structurally impossible when accept is typed up front.
 *
 * `accept*` therefore returns the **raw** stream so the caller can classify it — typically by
 * wrapping it in a [BufferedByteSource] and [BufferedByteSource.peek]ing the self-describing
 * prefix without consuming it — and then attach the right decoder per stream. A single-codec
 * typed view (a [StreamMux]) can be layered on top by wrapping each raw stream in a codec
 * adapter; that view lives with the codec-over-bytestream wrappers, outside this module.
 *
 * The four methods mirror [StreamMux] and return the tightest byte-level type per direction,
 * preventing impossible states at compile time:
 *
 * - [openBidirectional]: [ByteStream] — can send and receive
 * - [openUnidirectional]: [ByteSink] — send-only (announce end-of-send via [ByteSink.close])
 * - [acceptBidirectional]: [ByteStream] — peer-initiated duplex, raw
 * - [acceptUnidirectional]: [ByteSource] — peer-initiated receive-only, raw
 *
 * **Thread safety:** Implementations are NOT assumed to be thread-safe. Concurrent calls to
 * open/accept methods require external synchronization unless the implementation explicitly
 * documents otherwise.
 *
 * **Lifecycle:** ByteStreamMux does not own the connection lifecycle — the transport scope
 * does, exactly as for [StreamMux]. When the transport scope ends, all streams are
 * force-closed via structured concurrency.
 */
interface ByteStreamMux {
    /** Opens a client-initiated bidirectional stream. */
    suspend fun openBidirectional(): ByteStream

    /** Opens a client-initiated unidirectional (send-only) stream. */
    suspend fun openUnidirectional(): ByteSink

    /** Accepts a peer-initiated bidirectional stream. Suspends until one is opened by the peer. */
    suspend fun acceptBidirectional(): ByteStream

    /** Accepts a peer-initiated unidirectional (receive-only) stream. Suspends until one is opened by the peer. */
    suspend fun acceptUnidirectional(): ByteSource
}
