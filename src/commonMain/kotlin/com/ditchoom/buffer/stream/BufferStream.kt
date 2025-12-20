package com.ditchoom.buffer.stream

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.PooledBuffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Represents a stream of buffer chunks, simulating network/file IO.
 *
 * This is the foundation for zero-copy protocol parsing where data arrives
 * in chunks and needs to be processed without unnecessary memory copies.
 */
interface BufferStream {
    /**
     * Flow of buffer chunks as they arrive.
     * Each chunk should be processed and released before the next arrives.
     */
    val chunks: Flow<BufferChunk>

    /**
     * Total bytes available (if known), or -1 for streaming data.
     */
    val contentLength: Long
}

/**
 * A chunk of data from a stream.
 */
data class BufferChunk(
    val buffer: ReadBuffer,
    val isLast: Boolean,
    val offset: Long, // Byte offset in the overall stream
)

/**
 * Creates a BufferStream from raw byte data, splitting into chunks.
 * Useful for simulating network IO in tests and benchmarks.
 */
fun BufferStream(
    data: ByteArray,
    chunkSize: Int = 1024,
    pool: BufferPool,
): BufferStream = ByteArrayBufferStream(data, chunkSize, pool)

/**
 * Creates a BufferStream from a Flow of byte arrays.
 */
fun BufferStream(
    source: Flow<ByteArray>,
    pool: BufferPool,
): BufferStream = FlowBufferStream(source, pool)

private class ByteArrayBufferStream(
    private val data: ByteArray,
    private val chunkSize: Int,
    private val pool: BufferPool,
) : BufferStream {
    override val contentLength: Long = data.size.toLong()

    override val chunks: Flow<BufferChunk> =
        flow {
            var offset = 0L
            while (offset < data.size) {
                val remaining = data.size - offset.toInt()
                val size = minOf(chunkSize, remaining)
                val isLast = offset + size >= data.size

                val buffer = pool.acquire(size)
                buffer.writeBytes(data, offset.toInt(), size)
                buffer.resetForRead()

                emit(BufferChunk(buffer, isLast, offset))
                offset += size
            }
        }
}

private class FlowBufferStream(
    private val source: Flow<ByteArray>,
    private val pool: BufferPool,
) : BufferStream {
    override val contentLength: Long = -1L // Unknown for streaming

    override val chunks: Flow<BufferChunk> =
        flow {
            var offset = 0L
            var lastEmittedBuffer: PooledBuffer? = null

            source.collect { bytes ->
                // Release previous buffer
                lastEmittedBuffer?.release()

                val buffer = pool.acquire(bytes.size)
                buffer.writeBytes(bytes)
                buffer.resetForRead()

                // We don't know if this is the last chunk in a Flow
                emit(BufferChunk(buffer, isLast = false, offset))
                lastEmittedBuffer = buffer
                offset += bytes.size
            }

            // Mark the last one properly
            lastEmittedBuffer?.release()
        }
}

/**
 * Extension to collect a BufferStream into a single ByteArray.
 * Useful for small payloads where convenience outweighs performance.
 */
suspend fun BufferStream.collectToByteArray(): ByteArray {
    val result = mutableListOf<Byte>()
    chunks.collect { chunk ->
        val bytes = chunk.buffer.readByteArray(chunk.buffer.remaining())
        result.addAll(bytes.toList())
        if (chunk.buffer is PooledBuffer) {
            (chunk.buffer as PooledBuffer).release()
        }
    }
    return result.toByteArray()
}

/**
 * Accumulating buffer reader for parsing protocols that span multiple chunks.
 */
class AccumulatingBufferReader(
    private val pool: BufferPool,
    initialCapacity: Int = 8192,
) {
    private var accumulated = pool.acquire(initialCapacity)
    private var writePos = 0

    /**
     * Appends a chunk to the accumulated buffer.
     */
    fun append(chunk: BufferChunk) {
        val bytes = chunk.buffer.readByteArray(chunk.buffer.remaining())
        ensureCapacity(writePos + bytes.size)
        accumulated.position(writePos)
        accumulated.writeBytes(bytes)
        writePos += bytes.size
    }

    /**
     * Returns bytes available for reading.
     */
    fun available(): Int = writePos - accumulated.position()

    /**
     * Reads up to [count] bytes without consuming them.
     */
    fun peek(count: Int): ByteArray {
        val pos = accumulated.position()
        accumulated.setLimit(writePos)
        val available = minOf(count, writePos - pos)
        val result = ByteArray(available)
        for (i in 0 until available) {
            result[i] = accumulated.get(pos + i)
        }
        return result
    }

    /**
     * Reads a byte, advancing position.
     */
    fun readByte(): Byte {
        accumulated.setLimit(writePos)
        return accumulated.readByte()
    }

    /**
     * Reads bytes, advancing position.
     */
    fun readBytes(count: Int): ByteArray {
        accumulated.setLimit(writePos)
        return accumulated.readByteArray(count)
    }

    /**
     * Reads an Int, advancing position.
     */
    fun readInt(): Int {
        accumulated.setLimit(writePos)
        return accumulated.readInt()
    }

    /**
     * Reads a Short, advancing position.
     */
    fun readShort(): Short {
        accumulated.setLimit(writePos)
        return accumulated.readShort()
    }

    /**
     * Compacts the buffer, moving unread data to the beginning.
     */
    fun compact() {
        val pos = accumulated.position()
        val remaining = writePos - pos
        if (remaining > 0 && pos > 0) {
            val data = readBytes(remaining)
            accumulated.resetForWrite()
            accumulated.writeBytes(data)
            writePos = remaining
        } else if (pos > 0) {
            accumulated.resetForWrite()
            writePos = 0
        }
        accumulated.position(0)
    }

    /**
     * Releases resources.
     */
    fun release() {
        accumulated.release()
    }

    private fun ensureCapacity(required: Int) {
        if (required > accumulated.capacity) {
            val newSize = maxOf(accumulated.capacity * 2, required)
            val newBuffer = pool.acquire(newSize)

            // Copy existing data
            val pos = accumulated.position()
            accumulated.position(0)
            accumulated.setLimit(writePos)
            val data = accumulated.readByteArray(writePos)
            newBuffer.writeBytes(data)
            newBuffer.position(pos)

            accumulated.release()
            accumulated = newBuffer
        }
    }
}
