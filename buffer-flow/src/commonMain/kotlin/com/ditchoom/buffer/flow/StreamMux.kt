package com.ditchoom.buffer.flow

/**
 * A multiplexed connection that can open multiple independent typed streams.
 *
 * Models transports with native multiplexing (QUIC, HTTP/2). Each method returns
 * the tightest type for its stream direction, preventing impossible states at
 * compile time:
 *
 * - [openBidirectional]: returns [Connection] -- can send and receive
 * - [openUnidirectional]: returns [Sender] -- can only send (compile error to receive)
 * - [acceptBidirectional]: returns [Connection] -- peer-initiated bidirectional
 * - [acceptUnidirectional]: returns [Receiver] -- peer-initiated, can only receive
 *
 * Transports without multiplexing (TCP, WebSocket) don't implement this --
 * they provide a single [Connection] directly. No fake capabilities.
 */
interface StreamMux<T> {
    /** Opens a client-initiated bidirectional stream. */
    suspend fun openBidirectional(): Connection<T>

    /** Opens a client-initiated unidirectional (send-only) stream. */
    suspend fun openUnidirectional(): Sender<T>

    /** Accepts a peer-initiated bidirectional stream. Suspends until one is opened by the peer. */
    suspend fun acceptBidirectional(): Connection<T>

    /** Accepts a peer-initiated unidirectional (receive-only) stream. Suspends until one is opened by the peer. */
    suspend fun acceptUnidirectional(): Receiver<T>

    suspend fun close()
}
