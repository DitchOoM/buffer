package com.ditchoom.buffer.flow

/**
 * A typed, bidirectional message connection with a stable identity.
 *
 * Combines [Sender] and [Receiver] with lifecycle management. This is the primary
 * interface that protocol libraries code against -- they don't need to know whether
 * the underlying transport is TCP, WebSocket, QUIC, or in-memory.
 *
 * The [id] uniquely identifies this connection within its parent [StreamMux] (or is 0
 * for single-stream transports like TCP). It enables cross-layer log correlation:
 * the transport layer logs stream lifecycle by [id], the protocol layer logs decoded
 * messages by [id] -- both sides correlate without coupling.
 *
 * Implementations:
 * - `CodecConnection` (socket library): framing via Codec + ByteStream
 * - `ReconnectingConnection` (socket library): auto-reconnection wrapper
 * - In-memory pairs for testing
 */
interface Connection<T> :
    Sender<T>,
    Receiver<T> {
    /**
     * Opaque identifier for this connection/stream.
     *
     * For multiplexed transports (QUIC): the transport-assigned stream ID.
     * For single-stream transports (TCP): 0.
     * For in-memory test pairs: sequential counter.
     */
    val id: Long

    suspend fun close()
}
