package com.ditchoom.buffer.flow

import com.ditchoom.buffer.ReadBuffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
 */
interface ByteStream {
    val isOpen: Boolean

    /**
     * Reads the next chunk of bytes from the stream.
     *
     * @return [ReadResult.Data] with the bytes, [ReadResult.End] on clean EOF,
     *   or [ReadResult.Reset] if the peer reset the connection.
     */
    suspend fun read(timeout: Duration = 15.seconds): ReadResult

    /**
     * Writes [buffer] to the stream.
     *
     * @return number of bytes written
     */
    suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration = 15.seconds,
    ): BytesWritten

    /**
     * Writes multiple buffers in a single operation (gather write).
     * Default implementation writes each buffer sequentially.
     */
    suspend fun writeGathered(
        buffers: List<ReadBuffer>,
        timeout: Duration = 15.seconds,
    ): BytesWritten {
        var total = 0
        for (buf in buffers) total += write(buf, timeout).count
        return BytesWritten(total)
    }

    suspend fun close()
}
