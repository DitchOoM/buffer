package com.ditchoom.buffer.flow

import com.ditchoom.buffer.ReadBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

/**
 * Adapts a [Connection] back to a [ByteStream] for protocol layering.
 *
 * The [extract] function pulls a [ReadBuffer] from each inbound message.
 * Messages where [extract] returns null are skipped (e.g., control frames).
 * The [wrap] function wraps an outbound [ReadBuffer] into a message for sending.
 *
 * Zero-copy: just wraps/unwraps buffer references — no data is copied.
 *
 * The [scope] launches a coroutine that collects inbound messages into a channel,
 * bridging the push-based [Connection.receive] flow to the pull-based [ByteStream.read].
 *
 * ```
 * // WebSocket binary payloads as a ByteStream:
 * val byteStream = wsConnection.asByteStream(
 *     scope = connectionScope,
 *     extract = { (it as? WebSocketMessage.Binary)?.payload },
 *     wrap = { WebSocketMessage.Binary(it) },
 * )
 * ```
 */
fun <T> Connection<T>.asByteStream(
    scope: CoroutineScope,
    extract: (T) -> ReadBuffer?,
    wrap: (ReadBuffer) -> T,
): ByteStream = ConnectionByteStream(this, scope, extract, wrap)

private class ConnectionByteStream<T>(
    private val connection: Connection<T>,
    scope: CoroutineScope,
    private val extract: (T) -> ReadBuffer?,
    private val wrap: (ReadBuffer) -> T,
) : ByteStream {
    private val inbound = Channel<ReadBuffer>(Channel.UNLIMITED)
    private val collector: Job =
        scope.launch {
            try {
                connection.receive().collect { message ->
                    extract(message)?.let { inbound.send(it) }
                }
            } finally {
                inbound.close()
            }
        }

    override val isOpen: Boolean get() = collector.isActive

    override suspend fun read(timeout: Duration): ReadResult =
        try {
            ReadResult.Data(withTimeout(timeout) { inbound.receive() })
        } catch (_: ClosedReceiveChannelException) {
            ReadResult.End
        }

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): BytesWritten {
        val count = buffer.remaining()
        withTimeout(timeout) { connection.send(wrap(buffer)) }
        return BytesWritten(count)
    }

    override suspend fun close() {
        collector.cancel()
        connection.close()
    }
}
