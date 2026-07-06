package com.ditchoom.buffer.flow

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.encodeToPlatformBuffer
import com.ditchoom.buffer.codec.readFrame
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Views this byte [Receiver][ByteSource] as a typed [Receiver] of [T] by framing and decoding with
 * [codec].
 *
 * Each [receive] collection drives a private [StreamProcessor]: bytes read from this source are
 * appended, and every complete frame — sized by the codec's generated
 * [peekFrameSize][com.ditchoom.buffer.codec.FrameDetector.peekFrameSize] — is decoded and emitted
 * before the next transport read. A clean [ReadResult.End] completes the flow; a
 * [ReadResult.Reset] fails it with [ByteStreamResetException]. The processor and its pool are
 * released when collection ends (normally, on error, or on cancellation).
 *
 * @param codec the frame codec; must support frame detection (a self-delimiting wire format).
 * @param context decode context threaded to the codec (default [DecodeContext.Empty]).
 * @param frameByteOrder byte order for the framing [StreamProcessor]'s scalar peeks
 *   (default [ByteOrder.BIG_ENDIAN], the network default).
 */
public fun <T> ByteSource.typed(
    codec: Codec<T>,
    context: DecodeContext = DecodeContext.Empty,
    frameByteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): Receiver<T> = CodecSource(this, codec, context, frameByteOrder)

/**
 * Views this byte [Sender][ByteSink] as a typed [Sender] of [T] by encoding each message with
 * [codec] into a fresh buffer and writing it. The encode buffer is freed after each write, so no
 * native memory leaks across sends. [Sender.close] delegates to [ByteSink.close] (send-side FIN).
 *
 * @param codec the frame codec used to encode outbound messages.
 * @param factory allocator for the per-message encode buffer (default [BufferFactory.Default]).
 * @param context encode context threaded to the codec (default [EncodeContext.Empty]).
 */
public fun <T> ByteSink.typed(
    codec: Codec<T>,
    factory: BufferFactory = BufferFactory.Default,
    context: EncodeContext = EncodeContext.Empty,
): Sender<T> = CodecSink(this, codec, factory, context, id = 0L)

/**
 * Views this bidirectional [ByteStream] as a typed [Connection] of [T]: the receive half frames and
 * decodes inbound bytes (see [ByteSource.typed]) and the send half encodes outbound messages (see
 * [ByteSink.typed]). [Connection.close] closes the whole stream.
 *
 * Because [ByteStream] is more specific than [ByteSource] / [ByteSink], `stream.typed(codec)`
 * resolves to this overload and yields a full [Connection]; upcast to a single half first if you
 * want only a [Receiver] or [Sender].
 *
 * @param codec the frame codec used for both directions.
 * @param factory allocator for per-message encode buffers (default [BufferFactory.Default]).
 * @param decodeContext decode context threaded to the codec (default [DecodeContext.Empty]).
 * @param encodeContext encode context threaded to the codec (default [EncodeContext.Empty]).
 * @param frameByteOrder byte order for the framing [StreamProcessor] (default [ByteOrder.BIG_ENDIAN]).
 */
public fun <T> ByteStream.typed(
    codec: Codec<T>,
    factory: BufferFactory = BufferFactory.Default,
    decodeContext: DecodeContext = DecodeContext.Empty,
    encodeContext: EncodeContext = EncodeContext.Empty,
    frameByteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): Connection<T> = CodecConnection(this, codec, factory, decodeContext, encodeContext, frameByteOrder)

private class CodecSource<T>(
    private val source: ByteSource,
    private val codec: Codec<T>,
    private val context: DecodeContext,
    private val frameByteOrder: ByteOrder,
) : Receiver<T> {
    override fun receive(): Flow<T> =
        flow {
            val pool = BufferPool()
            val processor = StreamProcessor.create(pool, frameByteOrder)
            try {
                while (true) {
                    // Emit every frame already assembled before blocking on the next transport read.
                    while (true) {
                        val frame = codec.readFrame(processor, context) ?: break
                        emit(frame)
                    }
                    when (val result = source.read()) {
                        is ReadResult.Data -> processor.append(result.buffer)
                        ReadResult.End -> break
                        ReadResult.Reset -> throw ByteStreamResetException()
                    }
                }
            } finally {
                processor.release()
                pool.clear()
            }
        }
}

private class CodecSink<T>(
    private val sink: ByteSink,
    private val codec: Codec<T>,
    private val factory: BufferFactory,
    private val context: EncodeContext,
    override val id: Long,
) : Sender<T> {
    override suspend fun send(message: T) {
        val buffer = codec.encodeToPlatformBuffer(message, factory, context)
        try {
            sink.write(buffer)
        } finally {
            buffer.freeNativeMemory()
        }
    }

    override suspend fun close() {
        sink.close()
    }
}

private class CodecConnection<T>(
    private val stream: ByteStream,
    codec: Codec<T>,
    factory: BufferFactory,
    decodeContext: DecodeContext,
    encodeContext: EncodeContext,
    frameByteOrder: ByteOrder,
) : Connection<T> {
    private val sender = CodecSink(stream, codec, factory, encodeContext, id = 0L)
    private val receiver = CodecSource(stream, codec, decodeContext, frameByteOrder)

    override val id: Long get() = 0L

    override suspend fun send(message: T) = sender.send(message)

    override fun receive(): Flow<T> = receiver.receive()

    override suspend fun close() {
        stream.close()
    }
}
