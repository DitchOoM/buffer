package com.ditchoom.buffer.stream

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.PooledBuffer

/**
 * Represents a stream of buffer chunks for protocol parsing.
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
 * Creates a BufferStream from a ReadBuffer, splitting into chunks.
 * True zero-copy: uses slicing instead of copying data.
 * Useful for simulating network IO in tests and benchmarks without ByteArray allocation.
 */
fun BufferStream(
    buffer: ReadBuffer,
    chunkSize: Int = 1024,
): BufferStream = ReadBufferStream(buffer, chunkSize)

private class ReadBufferStream(
    private val source: ReadBuffer,
    private val chunkSize: Int,
) : BufferStream {
    override val contentLength: Long = source.remaining().toLong()

    override fun forEachChunk(handler: (BufferChunk) -> Unit) {
        var offset = 0L
        val totalSize = source.remaining()
        while (source.remaining() > 0) {
            val remaining = source.remaining()
            val size = minOf(chunkSize, remaining)
            val isLast = offset + size >= totalSize

            // Zero-copy: slice the source buffer directly
            val oldLimit = source.limit()
            source.setLimit(source.position() + size)
            val slice = source.slice()
            source.setLimit(oldLimit)
            source.position(source.position() + size)

            handler(BufferChunk(slice, isLast, offset))
            offset += size
        }
    }
}

/**
 * Collects a BufferStream into a single ReadBuffer.
 * For small streams, this creates a single contiguous buffer.
 */
fun BufferStream.collectToBuffer(pool: BufferPool): ReadBuffer {
    val processor = StreamProcessor.create(pool)
    forEachChunk { chunk ->
        processor.append(chunk.buffer)
    }
    return processor.readBuffer(processor.available())
}

/**
 * Zero-copy stream processor for parsing protocols that span multiple chunks.
 *
 * Design principles:
 * - Zero-copy reads when data is within a single chunk (common case)
 * - Minimal copying only when data spans chunk boundaries (rare)
 * - Chunks are released automatically when fully consumed
 *
 * Usage with network I/O:
 * ```kotlin
 * val processor = StreamProcessor.create(pool)
 *
 * // Reader coroutine fills buffers from network
 * for (chunk in channel) {
 *     processor.append(chunk)
 *
 *     // Parse complete messages
 *     while (processor.available() >= HEADER_SIZE) {
 *         val length = processor.peekInt()
 *         if (processor.available() >= HEADER_SIZE + length) {
 *             processor.skip(HEADER_SIZE)
 *             val payload = processor.readBuffer(length)  // Zero-copy when possible
 *             handleMessage(payload)
 *         } else {
 *             break  // Wait for more data
 *         }
 *     }
 * }
 *
 * processor.release()
 * ```
 */
class StreamProcessor private constructor(
    private val pool: BufferPool,
) {
    private val chunks = ArrayDeque<ReadBuffer>()
    private var totalAvailable = 0

    companion object {
        fun create(pool: BufferPool) = StreamProcessor(pool)
    }

    /**
     * Appends a chunk to the processor.
     * The processor takes ownership and will release PooledBuffers when consumed.
     */
    fun append(chunk: ReadBuffer) {
        if (chunk.remaining() > 0) {
            chunks.addLast(chunk)
            totalAvailable += chunk.remaining()
        }
    }

    /**
     * Returns total bytes available for reading across all chunks.
     */
    fun available(): Int = totalAvailable

    /**
     * Peeks at a byte without consuming it.
     */
    fun peekByte(offset: Int = 0): Byte {
        require(totalAvailable > offset) { "Not enough data: need ${offset + 1}, have $totalAvailable" }
        var remaining = offset
        for (chunk in chunks) {
            if (remaining < chunk.remaining()) {
                return chunk.get(chunk.position() + remaining)
            }
            remaining -= chunk.remaining()
        }
        throw IllegalStateException("Unexpected end of data")
    }

    /**
     * Peeks at an Int without consuming it.
     */
    fun peekInt(): Int {
        require(totalAvailable >= Int.SIZE_BYTES) { "Not enough data for Int" }
        val chunk = chunks.first()
        if (chunk.remaining() >= Int.SIZE_BYTES) {
            // Fast path: all in one chunk
            return chunk.getInt(chunk.position())
        }
        // Slow path: spans chunks
        return (peekByte(0).toInt() and 0xFF shl 24) or
            (peekByte(1).toInt() and 0xFF shl 16) or
            (peekByte(2).toInt() and 0xFF shl 8) or
            (peekByte(3).toInt() and 0xFF)
    }

    /**
     * Peeks at a Short without consuming it.
     */
    fun peekShort(): Short {
        require(totalAvailable >= Short.SIZE_BYTES) { "Not enough data for Short" }
        val chunk = chunks.first()
        if (chunk.remaining() >= Short.SIZE_BYTES) {
            return chunk.getShort(chunk.position())
        }
        return (
            (peekByte(0).toInt() and 0xFF shl 8) or
                (peekByte(1).toInt() and 0xFF)
        ).toShort()
    }

    /**
     * Finds the first mismatch between stream data and the given pattern.
     * Optimized to compare using Long/Int primitives when possible.
     *
     * @return -1 if the patterns match completely, or the index of first mismatch
     */
    fun peekMismatch(pattern: ReadBuffer): Int {
        val patternSize = pattern.remaining()
        if (totalAvailable < patternSize) return minOf(totalAvailable, patternSize)
        if (patternSize == 0) return -1

        val patternPos = pattern.position()
        val firstChunk = chunks.first()

        // Fast path: pattern fits entirely in first chunk - use primitive comparisons
        if (firstChunk.remaining() >= patternSize) {
            val chunkPos = firstChunk.position()
            var offset = 0

            // Compare 8 bytes at a time using Long
            while (offset + Long.SIZE_BYTES <= patternSize) {
                val chunkLong = firstChunk.getLong(chunkPos + offset)
                val patternLong = pattern.getLong(patternPos + offset)
                if (chunkLong != patternLong) {
                    // Find exact mismatch byte within this Long
                    return offset + findMismatchInLong(chunkLong, patternLong)
                }
                offset += Long.SIZE_BYTES
            }

            // Compare 4 bytes using Int
            if (offset + Int.SIZE_BYTES <= patternSize) {
                val chunkInt = firstChunk.getInt(chunkPos + offset)
                val patternInt = pattern.getInt(patternPos + offset)
                if (chunkInt != patternInt) {
                    return offset + findMismatchInInt(chunkInt, patternInt)
                }
                offset += Int.SIZE_BYTES
            }

            // Compare 2 bytes using Short
            if (offset + Short.SIZE_BYTES <= patternSize) {
                val chunkShort = firstChunk.getShort(chunkPos + offset)
                val patternShort = pattern.getShort(patternPos + offset)
                if (chunkShort != patternShort) {
                    return offset + findMismatchInShort(chunkShort, patternShort)
                }
                offset += Short.SIZE_BYTES
            }

            // Compare remaining byte
            if (offset < patternSize) {
                if (firstChunk.get(chunkPos + offset) != pattern.get(patternPos + offset)) {
                    return offset
                }
            }

            return -1
        }

        // Slow path: pattern spans chunks - compare byte by byte
        for (i in 0 until patternSize) {
            if (peekByte(i) != pattern.get(patternPos + i)) return i
        }
        return -1
    }

    /**
     * Checks if the next bytes match the given pattern.
     */
    fun peekMatches(pattern: ReadBuffer): Boolean = peekMismatch(pattern) < 0

    // TODO: Platform-specific optimization opportunity:
    // - JVM (JDK 11+): ByteBuffer.mismatch() or Arrays.mismatch()
    // - Native: memcmp via cinterop
    // - JS: Buffer.compare() in Node.js
    // Current implementation uses 8-byte Long comparisons which is reasonably efficient.

    // Find first mismatched byte index within a Long (big-endian)
    private fun findMismatchInLong(
        a: Long,
        b: Long,
    ): Int {
        val xor = a xor b
        return xor.countLeadingZeroBits() / 8
    }

    // Find first mismatched byte index within an Int (big-endian)
    private fun findMismatchInInt(
        a: Int,
        b: Int,
    ): Int {
        val xor = a xor b
        return xor.countLeadingZeroBits() / 8
    }

    // Find first mismatched byte index within a Short (big-endian)
    private fun findMismatchInShort(
        a: Short,
        b: Short,
    ): Int {
        val xor = (a.toInt() xor b.toInt()) and 0xFFFF
        // countLeadingZeroBits on Int counts 16 extra zeros, subtract them
        return (xor.countLeadingZeroBits() - 16) / 8
    }

    /**
     * Reads a byte, consuming it.
     */
    fun readByte(): Byte {
        require(totalAvailable >= 1) { "No data available" }
        val chunk = chunks.first()
        val byte = chunk.readByte()
        totalAvailable--
        removeChunkIfEmpty(chunk)
        return byte
    }

    /**
     * Reads an unsigned byte (0-255), consuming it.
     */
    fun readUnsignedByte(): Int = readByte().toInt() and 0xFF

    /**
     * Reads a Short, consuming it.
     */
    fun readShort(): Short {
        require(totalAvailable >= Short.SIZE_BYTES) { "Not enough data for Short" }
        val chunk = chunks.first()
        if (chunk.remaining() >= Short.SIZE_BYTES) {
            // Fast path: all in one chunk (zero-copy read)
            val value = chunk.readShort()
            totalAvailable -= Short.SIZE_BYTES
            removeChunkIfEmpty(chunk)
            return value
        }
        // Slow path: spans chunks
        return (
            (readByte().toInt() and 0xFF shl 8) or
                (readByte().toInt() and 0xFF)
        ).toShort()
    }

    /**
     * Reads an Int, consuming it.
     */
    fun readInt(): Int {
        require(totalAvailable >= Int.SIZE_BYTES) { "Not enough data for Int" }
        val chunk = chunks.first()
        if (chunk.remaining() >= Int.SIZE_BYTES) {
            // Fast path: all in one chunk (zero-copy read)
            val value = chunk.readInt()
            totalAvailable -= Int.SIZE_BYTES
            removeChunkIfEmpty(chunk)
            return value
        }
        // Slow path: spans chunks
        return (readByte().toInt() and 0xFF shl 24) or
            (readByte().toInt() and 0xFF shl 16) or
            (readByte().toInt() and 0xFF shl 8) or
            (readByte().toInt() and 0xFF)
    }

    /**
     * Reads a Long, consuming it.
     */
    fun readLong(): Long {
        require(totalAvailable >= Long.SIZE_BYTES) { "Not enough data for Long" }
        val chunk = chunks.first()
        if (chunk.remaining() >= Long.SIZE_BYTES) {
            // Fast path: all in one chunk (zero-copy read)
            val value = chunk.readLong()
            totalAvailable -= Long.SIZE_BYTES
            removeChunkIfEmpty(chunk)
            return value
        }
        // Slow path: spans chunks
        return (readByte().toLong() and 0xFF shl 56) or
            (readByte().toLong() and 0xFF shl 48) or
            (readByte().toLong() and 0xFF shl 40) or
            (readByte().toLong() and 0xFF shl 32) or
            (readByte().toLong() and 0xFF shl 24) or
            (readByte().toLong() and 0xFF shl 16) or
            (readByte().toLong() and 0xFF shl 8) or
            (readByte().toLong() and 0xFF)
    }

    /**
     * Reads a buffer of exactly [size] bytes.
     *
     * Returns a zero-copy slice when data is contiguous in one chunk.
     * Only copies when data spans multiple chunks.
     */
    fun readBuffer(size: Int): ReadBuffer {
        require(totalAvailable >= size) { "Not enough data: need $size, have $totalAvailable" }
        require(chunks.isNotEmpty() || size == 0) { "No chunks available" }

        val chunk = chunks.firstOrNull()
        if (chunk == null || size == 0) {
            // Empty read - return empty slice from pool
            val empty = pool.acquire(0)
            empty.resetForRead()
            return empty
        }
        if (chunk.remaining() >= size) {
            // Fast path: all in one chunk - return slice (ZERO-COPY!)
            val oldLimit = chunk.limit()
            chunk.setLimit(chunk.position() + size)
            val slice = chunk.slice()
            chunk.setLimit(oldLimit)
            chunk.position(chunk.position() + size)
            totalAvailable -= size
            removeChunkIfEmpty(chunk)
            return slice
        }

        // Slow path: spans chunks - need to copy (rare)
        val merged = pool.acquire(size)
        var remaining = size
        while (remaining > 0 && chunks.isNotEmpty()) {
            val currentChunk = chunks.first()
            val toCopy = minOf(remaining, currentChunk.remaining())

            // Copy using bulk operation when possible
            val oldLimit = currentChunk.limit()
            currentChunk.setLimit(currentChunk.position() + toCopy)
            merged.write(currentChunk)
            currentChunk.setLimit(oldLimit)

            remaining -= toCopy
            totalAvailable -= toCopy
            removeChunkIfEmpty(currentChunk)
        }
        merged.resetForRead()
        return merged
    }

    /**
     * Skips [count] bytes.
     */
    fun skip(count: Int) {
        require(totalAvailable >= count) { "Not enough data to skip: need $count, have $totalAvailable" }
        var remaining = count
        while (remaining > 0 && chunks.isNotEmpty()) {
            val chunk = chunks.first()
            val toSkip = minOf(remaining, chunk.remaining())
            chunk.position(chunk.position() + toSkip)
            remaining -= toSkip
            totalAvailable -= toSkip
            removeChunkIfEmpty(chunk)
        }
    }

    /**
     * Releases all resources. Call when done processing.
     */
    fun release() {
        for (chunk in chunks) {
            releaseIfPooled(chunk)
        }
        chunks.clear()
        totalAvailable = 0
    }

    private fun removeChunkIfEmpty(chunk: ReadBuffer) {
        if (chunk.remaining() == 0) {
            chunks.removeFirst()
            releaseIfPooled(chunk)
        }
    }

    private fun releaseIfPooled(buffer: ReadBuffer) {
        if (buffer is PooledBuffer) {
            buffer.release()
        }
    }
}
