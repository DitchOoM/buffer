package com.ditchoom.buffer.flow

import com.ditchoom.buffer.ReadBuffer
import kotlin.time.Duration

/**
 * The read half of a byte stream.
 *
 * The deadline policy lives on the type as a [readPolicy] **val**, not as a defaulted parameter
 * value. The only abstract operation is [read] with an explicit [deadline]; the no-arg [read]
 * is a non-abstract convenience that consults [readPolicy]. Because [readPolicy] is a `val`,
 * an implementation can override it (`override val readPolicy = ReadPolicy.UntilClosed`) — which a
 * default parameter value can never be. That is what makes the persistent-stream timeout footgun
 * structurally impossible.
 */
interface ByteSource {
    val isOpen: Boolean

    /** The read deadline policy for this source. Override per implementation; see [ReadPolicy]. */
    val readPolicy: ReadPolicy

    /**
     * Reads the next chunk of bytes, waiting at most [deadline].
     *
     * @return [ReadResult.Data] with the bytes, [ReadResult.End] on clean EOF,
     *   or [ReadResult.Reset] if the peer reset the connection.
     */
    suspend fun read(deadline: Duration): ReadResult

    /** Reads the next chunk using the injected [readPolicy] — no inherited-default deadline. */
    suspend fun read(): ReadResult = read(readPolicy.toDeadline())
}

/**
 * The write half of a byte stream.
 *
 * Mirrors [ByteSource]: the deadline policy is the [writePolicy] **val**, the explicit-deadline
 * overloads are abstract, and the no-arg overloads consult [writePolicy].
 */
interface ByteSink {
    val isOpen: Boolean

    /** The write deadline policy for this sink. Override per implementation; see [WritePolicy]. */
    val writePolicy: WritePolicy

    /**
     * Writes [buffer], waiting at most [deadline].
     *
     * @return number of bytes written
     */
    suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten

    /** Writes [buffer] using the injected [writePolicy]. */
    suspend fun write(buffer: ReadBuffer): BytesWritten = write(buffer, writePolicy.toDeadline())

    /**
     * Writes multiple buffers in a single operation (gather write), waiting at most [deadline].
     * Default implementation writes each buffer sequentially.
     */
    suspend fun writeGathered(
        buffers: List<ReadBuffer>,
        deadline: Duration,
    ): BytesWritten {
        var total = 0
        for (buf in buffers) total += write(buf, deadline).count
        return BytesWritten(total)
    }

    /** Gather-writes [buffers] using the injected [writePolicy]. */
    suspend fun writeGathered(buffers: List<ReadBuffer>): BytesWritten = writeGathered(buffers, writePolicy.toDeadline())
}

/**
 * A bidirectional byte stream — the fundamental transport abstraction.
 *
 * Protocol libraries code against this interface. Transport implementations
 * (TCP, QUIC, unix sockets, in-memory) provide it. This enables protocol
 * layering: a [Connection] over one protocol can be adapted back to a
 * [ByteStream] for the next protocol layer.
 *
 * ```
 * ByteStream (TCP) → Connection<WebSocketMessage> → ByteStream (WS binary)
 *     → Connection<MqttPacket>
 * ```
 *
 * The byte trichotomy ([ByteSource] / [ByteSink] / [ByteStream]) mirrors the typed-message
 * trichotomy ([Receiver] / [Sender] / [Connection]) exactly: tightest type per direction, no
 * fake capabilities. A send-only stream is a [ByteSink], not a [ByteStream] with a stubbed read.
 */
interface ByteStream :
    ByteSource,
    ByteSink {
    suspend fun close()
}

/**
 * A [ByteStream] that can finish its **send** side independently of its read side.
 *
 * [shutdownSend] sends a stream-level end-of-send (e.g. a QUIC FIN) so the peer sees
 * end-of-request, while leaving the read side open to receive the response — the half-close that
 * HTTP/3 request/response needs (RFC 9114 §4). After [shutdownSend], [read] still works; [write] fails.
 */
interface HalfCloseable : ByteStream {
    /** Send a send-side FIN. Idempotent; a no-op once the stream is fully [close]d. */
    suspend fun shutdownSend()
}

/**
 * A byte stream half that can be abruptly **reset** with an application error code — RESET_STREAM on
 * the send side and STOP_SENDING on the receive side (RFC 9000 §19.4/§19.5) — rather than the
 * graceful FIN of [ByteStream.close]. Used by layered protocols to abort a single stream and tell the
 * peer *why* (e.g. an HTTP/3 error code, RFC 9114 §8.1).
 *
 * Deliberately **not** a [ByteStream]: reset is an orthogonal capability that applies to a duplex
 * [ByteStream], a send-only [ByteSink], or a receive-only [ByteSource] alike (a unidirectional QUIC /
 * WebTransport stream is resettable without being bidirectional). Mix it onto whichever direction the
 * stream actually has — `ByteSink, Resettable` for send-only, `ByteSource, Resettable` for receive-only.
 */
interface Resettable {
    /** Abort the stream with [errorCode]. Idempotent; subsequent reads/writes fail like after a close. */
    suspend fun reset(errorCode: Long)
}
