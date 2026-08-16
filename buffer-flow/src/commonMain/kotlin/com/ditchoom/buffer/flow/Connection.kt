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
 * `OutboundWriter` is the reusable component that provides both, and the socket library's
 * `CodecConnection`/`CodecSender` are built on it.
 *
 * **Not every implementation conforms yet, and the exceptions matter.** In this module,
 * [ByteSink.typed] and [ByteStream.typed] encode and `writeFully` on the *caller's* coroutine with
 * no serialization: two concurrent sends can interleave partial writes, and for a self-framing
 * codec the peer then reads a length prefix across the splice point — a silent, permanent stream
 * desync. **Keep your external `Mutex` around sends through those two**, and do not read the
 * guarantee above as already true of them. Third-party implementations written against the pre-6.x
 * contract ("not assumed thread-safe, bring your own Mutex") are in the same position.
 *
 * v7 makes the guarantee mandatory for all implementors; retrofitting this module's typed views
 * onto `OutboundWriter` is tracked as part of that milestone.
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
