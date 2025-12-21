package com.ditchoom.buffer.stream

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.pool.BufferPool

/**
 * Represents a stream of buffer chunks, simulating network/file IO.
 *
 * This is the foundation for zero-copy protocol parsing where data arrives
 * in chunks and needs to be processed without unnecessary memory copies.
 */
interface BufferStream {
    /**
     * Processes buffer chunks as they arrive via callback.
     * Each chunk should be processed and released before the next arrives.
     */
    fun forEachChunk(handler: (BufferChunk) -> Unit)

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

private class ByteArrayBufferStream(
    private val data: ByteArray,
    private val chunkSize: Int,
    private val pool: BufferPool,
) : BufferStream {
    override val contentLength: Long = data.size.toLong()

    override fun forEachChunk(handler: (BufferChunk) -> Unit) {
        var offset = 0L
        while (offset < data.size) {
            val remaining = data.size - offset.toInt()
            val size = minOf(chunkSize, remaining)
            val isLast = offset + size >= data.size

            val buffer = pool.acquire(size)
            buffer.writeBytes(data, offset.toInt(), size)
            buffer.resetForRead()

            try {
                handler(BufferChunk(buffer, isLast, offset))
            } finally {
                buffer.release()
            }
            offset += size
        }
    }
}

/**
 * Extension to collect a BufferStream into a single ByteArray.
 * Useful for small payloads where convenience outweighs performance.
 */
fun BufferStream.collectToByteArray(): ByteArray {
    val result = mutableListOf<Byte>()
    forEachChunk { chunk ->
        val bytes = chunk.buffer.readByteArray(chunk.buffer.remaining())
        result.addAll(bytes.toList())
    }
    return result.toByteArray()
}

/**
 * Accumulating buffer reader for parsing protocols that span multiple chunks.
 * Optimized to avoid ByteArray allocations - works directly with buffers.
 */
class AccumulatingBufferReader(
    private val pool: BufferPool,
    initialCapacity: Int = 8192,
) {
    private var accumulated = pool.acquire(initialCapacity)
    private var writePos = 0

    /**
     * Appends a chunk to the accumulated buffer.
     * Zero-copy when possible - reads directly from source buffer to accumulated buffer.
     */
    fun append(chunk: BufferChunk) {
        val size = chunk.buffer.remaining()
        ensureCapacity(writePos + size)
        val currentReadPos = accumulated.position()
        accumulated.setLimit(accumulated.capacity)
        accumulated.position(writePos)
        // Direct buffer-to-buffer copy without intermediate ByteArray
        accumulated.write(chunk.buffer)
        writePos += size
        accumulated.position(currentReadPos)
    }

    /**
     * Returns bytes available for reading.
     */
    fun available(): Int = writePos - accumulated.position()

    /**
     * Peeks at a single byte without consuming it.
     */
    fun peekByte(offset: Int = 0): Byte {
        val pos = accumulated.position()
        return accumulated.get(pos + offset)
    }

    /**
     * Checks if the next [count] bytes match the given pattern.
     */
    fun peekMatches(pattern: ByteArray): Boolean {
        val pos = accumulated.position()
        if (writePos - pos < pattern.size) return false
        for (i in pattern.indices) {
            if (accumulated.get(pos + i) != pattern[i]) return false
        }
        return true
    }

    /**
     * Reads a byte, advancing position.
     */
    fun readByte(): Byte {
        accumulated.setLimit(writePos)
        return accumulated.readByte()
    }

    /**
     * Reads an unsigned byte, advancing position.
     */
    fun readUnsignedByte(): Int = readByte().toInt() and 0xFF

    /**
     * Reads a slice of the buffer as a ReadBuffer, advancing position.
     * The returned buffer is a zero-copy view when the platform supports it.
     */
    fun readBuffer(count: Int): ReadBuffer {
        accumulated.setLimit(writePos)
        val oldLimit = accumulated.limit()
        accumulated.setLimit(accumulated.position() + count)
        val slice = accumulated.slice()
        accumulated.position(accumulated.position() + count)
        accumulated.setLimit(oldLimit)
        return slice
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
     * Reads a Long, advancing position.
     */
    fun readLong(): Long {
        accumulated.setLimit(writePos)
        return accumulated.readLong()
    }

    /**
     * Skips [count] bytes.
     */
    fun skip(count: Int) {
        accumulated.position(accumulated.position() + count)
    }

    /**
     * Compacts the buffer, moving unread data to the beginning.
     * Uses buffer-to-buffer copy without intermediate ByteArray.
     */
    fun compact() {
        val pos = accumulated.position()
        val remaining = writePos - pos
        if (remaining > 0 && pos > 0) {
            // Create a slice of unread data
            accumulated.setLimit(writePos)
            val unreadSlice = accumulated.slice()

            // Reset and write the slice back at the beginning
            accumulated.resetForWrite()
            accumulated.write(unreadSlice)
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

            // Copy existing data using buffer-to-buffer copy
            val pos = accumulated.position()
            accumulated.position(0)
            accumulated.setLimit(writePos)
            newBuffer.write(accumulated)
            newBuffer.position(pos)

            accumulated.release()
            accumulated = newBuffer
        }
    }
}
