package com.ditchoom.buffer.ktor

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Encoder
import com.ditchoom.buffer.codec.encodeToPlatformBuffer
import com.ditchoom.buffer.codec.readFrame
import com.ditchoom.buffer.kotlinxio.copyToPlatformBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val DEFAULT_FRAME_CHUNK_SIZE = 8 * 1024

/**
 * Reads self-delimiting frames from this [ByteReadChannel] and decodes each with [codec], emitting
 * decoded values as a cold [Flow].
 *
 * Each collection drives a private [StreamProcessor]: channel bytes are pulled in chunks and
 * appended, and every complete frame — sized by the codec's generated
 * [peekFrameSize][com.ditchoom.buffer.codec.FrameDetector.peekFrameSize] — is decoded and emitted
 * before the next channel read. The flow completes when the channel closes for read; a trailing
 * partial frame at clean close is discarded. The processor and its chunk pool are released when
 * collection ends (normally, on error, or on cancellation).
 *
 * The wire format must be self-delimiting (a length prefix, fixed size, or sealed discriminator);
 * [codec] must therefore support frame detection or [readFrame] throws. This helper adds no framing
 * of its own — pair it with [encodeFrame], which writes exactly the codec's bytes.
 *
 * @param codec the frame codec; must support frame detection.
 * @param context decode context threaded to the codec (default [DecodeContext.Empty]).
 * @param frameByteOrder byte order for the framing [StreamProcessor] and appended chunks
 *   (default [ByteOrder.BIG_ENDIAN], the network default).
 * @param chunkSize maximum bytes pulled from the channel per read (default 8 KiB).
 */
public fun <T> ByteReadChannel.decodeFrames(
    codec: Codec<T>,
    context: DecodeContext = DecodeContext.Empty,
    frameByteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    chunkSize: Int = DEFAULT_FRAME_CHUNK_SIZE,
): Flow<T> =
    flow {
        val pool = BufferPool()
        val processor = StreamProcessor.create(pool, frameByteOrder)
        try {
            while (awaitContent(1)) {
                val incoming = readBuffer(chunkSize)
                if (incoming.exhausted()) break
                processor.append(incoming.copyToPlatformBuffer(pool, frameByteOrder))
                while (true) {
                    val frame = codec.readFrame(processor, context) ?: break
                    emit(frame)
                }
            }
        } finally {
            processor.release()
            pool.clear()
        }
    }

/**
 * Encodes [value] with [codec] into a fresh buffer and writes exactly those bytes to this
 * [ByteWriteChannel], then flushes. The encode buffer is freed after the write, so repeated
 * [encodeFrame] calls never leak native memory.
 *
 * Writes only the codec's own bytes — no extra length prefix — so the wire format must be
 * self-delimiting for [decodeFrames] to re-frame it on the far side.
 *
 * @param codec the frame codec used to encode [value].
 * @param value the value to encode and send.
 * @param factory allocator for the encode buffer (default [BufferFactory.Default]).
 * @param context encode context threaded to the codec (default [EncodeContext.Empty]).
 */
public suspend fun <T> ByteWriteChannel.encodeFrame(
    codec: Encoder<T>,
    value: T,
    factory: BufferFactory = BufferFactory.Default,
    context: EncodeContext = EncodeContext.Empty,
) {
    val buffer = codec.encodeToPlatformBuffer(value, factory, context)
    try {
        writeBuffer(buffer)
    } finally {
        buffer.freeNativeMemory()
    }
}
