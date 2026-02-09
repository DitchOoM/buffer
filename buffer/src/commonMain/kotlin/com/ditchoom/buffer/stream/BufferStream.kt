package com.ditchoom.buffer.stream

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.pool.BufferPool

/**
 * Represents a stream of buffer chunks for protocol parsing.
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
 * Uses slicing to avoid copying data.
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
 * Stream processor for parsing protocols that span multiple chunks.
 *
 * Design principles:
 * - Returns slices when data is within a single chunk (common case)
 * - Copies only when data spans chunk boundaries
 * - Chunks are released automatically when fully consumed
 *
 * Example usage:
 * ```kotlin
 * val processor = StreamProcessor.create(pool)
 *
 * for (chunk in channel) {
 *     processor.append(chunk)
 *
 *     while (processor.available() >= HEADER_SIZE) {
 *         val length = processor.peekInt()
 *         if (processor.available() >= HEADER_SIZE + length) {
 *             processor.skip(HEADER_SIZE)
 *             val payload = processor.readBuffer(length)
 *             handleMessage(payload)
 *         } else {
 *             break
 *         }
 *     }
 * }
 *
 * processor.release()
 * ```
 */
interface StreamProcessor {
    /**
     * Appends a chunk to the processor.
     * The processor takes ownership and will free PlatformBuffers when consumed.
     */
    fun append(chunk: ReadBuffer)

    /**
     * Returns total bytes available for reading across all chunks.
     */
    fun available(): Int

    /**
     * Peeks at a byte without consuming it.
     * @param offset byte offset from current position (default 0)
     */
    fun peekByte(offset: Int = 0): Byte

    /**
     * Peeks at a Short without consuming it.
     * @param offset byte offset from current position (default 0)
     */
    fun peekShort(offset: Int = 0): Short

    /**
     * Peeks at an Int without consuming it.
     * @param offset byte offset from current position (default 0)
     */
    fun peekInt(offset: Int = 0): Int

    /**
     * Peeks at a Long without consuming it.
     * @param offset byte offset from current position (default 0)
     */
    fun peekLong(offset: Int = 0): Long

    /**
     * Finds the first mismatch between stream data and the given pattern.
     * Optimized to compare using Long/Int primitives when possible.
     *
     * @return -1 if the patterns match completely, or the index of first mismatch
     */
    fun peekMismatch(pattern: ReadBuffer): Int

    /**
     * Checks if the next bytes match the given pattern.
     */
    fun peekMatches(pattern: ReadBuffer): Boolean

    /**
     * Reads a byte, consuming it.
     */
    fun readByte(): Byte

    /**
     * Reads an unsigned byte (0-255), consuming it.
     */
    fun readUnsignedByte(): Int

    /**
     * Reads a Short, consuming it.
     */
    fun readShort(): Short

    /**
     * Reads an Int, consuming it.
     */
    fun readInt(): Int

    /**
     * Reads a Long, consuming it.
     */
    fun readLong(): Long

    /**
     * Reads a buffer of exactly [size] bytes.
     *
     * Returns a slice when data is contiguous in one chunk.
     * Copies when data spans multiple chunks.
     */
    fun readBuffer(size: Int): ReadBuffer

    /**
     * Skips [count] bytes.
     */
    fun skip(count: Int)

    /**
     * Signals that all data has been appended and flushes any buffered data.
     * Call this after appending all chunks but before reading final data.
     *
     * This is important for transforms that buffer data (e.g., decompression).
     * The default implementation does nothing.
     */
    fun finish() {}

    /**
     * Releases all resources. Call when done processing.
     * Implicitly calls [finish] if not already called.
     */
    fun release()

    companion object {
        fun create(pool: BufferPool): StreamProcessor = DefaultStreamProcessor(pool)
    }
}

/**
 * Default implementation of StreamProcessor.
 */
internal class DefaultStreamProcessor(
    private val pool: BufferPool,
) : StreamProcessor {
    private val chunks = ArrayDeque<ReadBuffer>()
    private var totalAvailable = 0

    override fun append(chunk: ReadBuffer) {
        if (chunk.remaining() > 0) {
            chunks.addLast(chunk)
            totalAvailable += chunk.remaining()
        }
    }

    override fun available(): Int = totalAvailable

    override fun peekByte(offset: Int): Byte {
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

    override fun peekShort(offset: Int): Short {
        require(totalAvailable >= offset + Short.SIZE_BYTES) { "Not enough data for Short at offset $offset" }

        val chunk = chunks.first()
        if (chunk.remaining() >= offset + Short.SIZE_BYTES) {
            return chunk.getShort(chunk.position() + offset)
        }

        // Slow path: spans chunks
        return (
            (peekByte(offset).toInt() and 0xFF shl 8) or
                (peekByte(offset + 1).toInt() and 0xFF)
        ).toShort()
    }

    override fun peekInt(offset: Int): Int {
        require(totalAvailable >= offset + Int.SIZE_BYTES) { "Not enough data for Int at offset $offset" }

        val chunk = chunks.first()
        if (chunk.remaining() >= offset + Int.SIZE_BYTES) {
            return chunk.getInt(chunk.position() + offset)
        }

        // Slow path: spans chunks
        return (peekByte(offset).toInt() and 0xFF shl 24) or
            (peekByte(offset + 1).toInt() and 0xFF shl 16) or
            (peekByte(offset + 2).toInt() and 0xFF shl 8) or
            (peekByte(offset + 3).toInt() and 0xFF)
    }

    override fun peekLong(offset: Int): Long {
        require(totalAvailable >= offset + Long.SIZE_BYTES) { "Not enough data for Long at offset $offset" }

        val chunk = chunks.first()
        if (chunk.remaining() >= offset + Long.SIZE_BYTES) {
            return chunk.getLong(chunk.position() + offset)
        }

        // Slow path: spans chunks
        return (peekByte(offset).toLong() and 0xFF shl 56) or
            (peekByte(offset + 1).toLong() and 0xFF shl 48) or
            (peekByte(offset + 2).toLong() and 0xFF shl 40) or
            (peekByte(offset + 3).toLong() and 0xFF shl 32) or
            (peekByte(offset + 4).toLong() and 0xFF shl 24) or
            (peekByte(offset + 5).toLong() and 0xFF shl 16) or
            (peekByte(offset + 6).toLong() and 0xFF shl 8) or
            (peekByte(offset + 7).toLong() and 0xFF)
    }

    override fun peekMismatch(pattern: ReadBuffer): Int {
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

    override fun peekMatches(pattern: ReadBuffer): Boolean = peekMismatch(pattern) < 0

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

    override fun readByte(): Byte {
        require(totalAvailable >= 1) { "No data available" }
        val chunk = chunks.first()
        val byte = chunk.readByte()
        totalAvailable--
        removeChunkIfEmpty(chunk)
        return byte
    }

    override fun readUnsignedByte(): Int = readByte().toInt() and 0xFF

    override fun readShort(): Short {
        require(totalAvailable >= Short.SIZE_BYTES) { "Not enough data for Short" }
        val chunk = chunks.first()
        if (chunk.remaining() >= Short.SIZE_BYTES) {
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

    override fun readInt(): Int {
        require(totalAvailable >= Int.SIZE_BYTES) { "Not enough data for Int" }
        val chunk = chunks.first()
        if (chunk.remaining() >= Int.SIZE_BYTES) {
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

    override fun readLong(): Long {
        require(totalAvailable >= Long.SIZE_BYTES) { "Not enough data for Long" }
        val chunk = chunks.first()
        if (chunk.remaining() >= Long.SIZE_BYTES) {
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

    override fun readBuffer(size: Int): ReadBuffer {
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
            // Data is contiguous, return a slice
            val oldLimit = chunk.limit()
            chunk.setLimit(chunk.position() + size)
            val slice = chunk.slice()
            chunk.setLimit(oldLimit)
            chunk.position(chunk.position() + size)
            totalAvailable -= size
            removeChunkIfEmpty(chunk)
            return slice
        }

        // Data spans multiple chunks, need to copy
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

    override fun skip(count: Int) {
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

    override fun release() {
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
        // Use pool.release() rather than freeNativeMemory() because readBuffer()
        // returns slices that reference the parent chunk's memory. Freeing the
        // parent would invalidate those slices. Pool release keeps memory alive.
        if (buffer is PlatformBuffer) {
            pool.release(buffer)
        }
    }
}
