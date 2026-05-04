package com.ditchoom.buffer.flow.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.DEFAULT_NETWORK_BUFFER_SIZE
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.use
import com.ditchoom.buffer.withPooling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Wraps a [ByteStream] as a typed [Connection] using the supplied [Codec] for
 * framing.
 *
 * The receive path appends each inbound chunk to a [StreamProcessor] backed by
 * [pool], drives a `peekFrameSize` → `readBufferScoped(decode)` loop, and emits
 * decoded values to a [Channel]. The send path encodes each outbound value
 * into a freshly-allocated buffer drawn from a pooling factory over the same
 * pool, hands it to [ByteStream.write], and frees back to the pool on close.
 *
 * Both directions share [pool] so the caller has a single budget to tune.
 *
 * @param codec      framing + payload codec; must support [Codec.peekFrameSize]
 *                   for the receive loop to make progress
 * @param pool       buffer pool used for both [StreamProcessor] chunk staging
 *                   on receive and pooled allocation on send
 * @param scope      coroutine scope hosting the receive collector; the
 *                   collector is cancelled by [Connection.close]
 * @param id         opaque identifier surfaced via [Connection.id]
 * @param byteOrder  wire byte order for the [StreamProcessor] (peek-time
 *                   primitive reads honour this); defaults to big-endian
 * @param sendBufferSize fallback allocation size when [Codec.wireSize] returns
 *                   [WireSize.BackPatch]; defaults to [DEFAULT_NETWORK_BUFFER_SIZE]
 */
fun <T : Any> ByteStream.asCodecConnection(
    codec: Codec<T>,
    pool: BufferPool,
    scope: CoroutineScope,
    id: Long = 0,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    sendBufferSize: Int = DEFAULT_NETWORK_BUFFER_SIZE,
): Connection<T> = CodecConnection(this, codec, pool, scope, id, byteOrder, sendBufferSize)

private class CodecConnection<T : Any>(
    private val byteStream: ByteStream,
    private val codec: Codec<T>,
    private val pool: BufferPool,
    scope: CoroutineScope,
    override val id: Long,
    byteOrder: ByteOrder,
    private val sendBufferSize: Int,
) : Connection<T> {
    private val sendFactory: BufferFactory = BufferFactory.Default.withPooling(pool)
    private val inbound = Channel<T>(Channel.UNLIMITED)
    private val collector: Job =
        scope.launch {
            val processor = StreamProcessor.create(pool, byteOrder)
            try {
                while (true) {
                    when (val read = byteStream.read()) {
                        is ReadResult.Data -> {
                            processor.append(read.buffer)
                            drainFrames(processor)
                        }
                        ReadResult.End, ReadResult.Reset -> break
                    }
                }
            } finally {
                processor.release()
                inbound.close()
            }
        }

    private fun drainFrames(processor: StreamProcessor) {
        while (true) {
            when (val peek = codec.peekFrameSize(processor)) {
                is PeekResult.Complete -> {
                    val decoded =
                        processor.readBufferScoped(peek.bytes) {
                            codec.decode(this, DecodeContext.Empty)
                        }
                    inbound.trySend(decoded)
                }
                PeekResult.NeedsMoreData -> return
                PeekResult.NoFraming ->
                    error(
                        "Codec ${codec::class.simpleName} reports NoFraming — " +
                            "cannot drive a streaming receive loop. Use a codec that " +
                            "implements peekFrameSize, or a different bridge.",
                    )
            }
        }
    }

    override fun receive(): Flow<T> = inbound.receiveAsFlow()

    override suspend fun send(message: T) {
        val size =
            when (val ws = codec.wireSize(message, EncodeContext.Empty)) {
                is WireSize.Exact -> ws.bytes
                WireSize.BackPatch -> sendBufferSize
            }
        sendFactory.allocate(size).use { buf ->
            codec.encode(buf, message, EncodeContext.Empty)
            buf.resetForRead()
            byteStream.write(buf)
        }
    }

    override suspend fun close() {
        collector.cancel()
        byteStream.close()
    }
}
