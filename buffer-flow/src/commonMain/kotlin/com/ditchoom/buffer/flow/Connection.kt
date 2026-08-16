package com.ditchoom.buffer.flow

/**
 * A typed, bidirectional message connection with a stable identity.
 *
 * **Send contract (staged toward v7):** a *conforming* implementation guarantees, with no
 * external synchronization from callers, that
 *
 * 1. [send] is **atomic** — a message reaches the peer whole or not at all; cancelling a sender
 *    can never leave a partial frame on the wire; and
 * 2. [send] is **serialized** — concurrent `send`s on one connection never interleave their
 *    bytes.
 *
 * Every implementation in this ecosystem conforms (they own their writer — see `OutboundWriter`
 * for the reusable component). Third-party implementations written against the pre-6.x contract
 * ("not assumed thread-safe, bring your own Mutex") remain *callable* but are not conforming;
 * v7 makes the guarantee mandatory for all implementors. Callers should already treat the two
 * properties above as the contract and stop wrapping sends in external locks.
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
    override val id: Long

    /** Close the whole connection (re-abstracts [Sender.close], which is send-side-only). */
    override suspend fun close()

    /**
     * Close the whole connection **immediately**, without draining queued sends — the RST-like
     * counterpart to the graceful [close], mirroring the byte layer's [Resettable] against
     * [ByteStream.close]'s FIN. Queued-but-unwritten messages are equivalent to messages lost by
     * the network; implementations report them through their loss path where one exists.
     *
     * Defaults to [close] so existing implementations remain source- and behavior-compatible;
     * implementations with a real abort (a transport reset, an owned writer to cancel) override.
     * Idempotent.
     */
    suspend fun abort() {
        close()
    }
}
