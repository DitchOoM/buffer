package com.ditchoom.buffer.stream

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadWriteBuffer
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
     * The processor takes ownership and will free buffers when consumed.
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
     * The returned buffer has [ReadBuffer.remaining] == [size] and is ready to read
     * from its current position. **Do not assume position is 0** — the buffer may be
     * a view/slice of a larger chunk where position > 0 is correct.
     *
     * Returns the chunk directly when data fits exactly, or a slice when contiguous.
     * Copies when data spans multiple chunks.
     *
     * The returned buffer is not released back to the pool. For proper pool recycling,
     * prefer [readBufferScoped].
     */
    fun readBuffer(size: Int): ReadBuffer

    /**
     * Reads exactly [size] bytes and passes them to [block], then releases the buffer back to
     * the pool. The buffer must not escape the [block] scope — copy data if you need it longer.
     *
     * Prefer this over [readBuffer] when pool recycling matters.
     */
    fun <T> readBufferScoped(
        size: Int,
        block: ReadBuffer.() -> T,
    ): T

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
        fun create(
            pool: BufferPool,
            byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
        ): StreamProcessor = DefaultStreamProcessor(pool, byteOrder)
    }
}

/**
 * Default implementation of StreamProcessor.
 *
 * Small appends (< [COALESCE_THRESHOLD] bytes) are copied into a coalescing tail buffer
 * to reduce the chunk count. This turns O(n) peek scans into O(1) when thousands of tiny
 * network fragments arrive (e.g., 1 MB in 64-byte chunks → ~64 coalesced chunks instead
 * of 16,384). Large appends bypass coalescing entirely (zero-copy).
 */
internal class DefaultStreamProcessor(
    private val pool: BufferPool,
    private val byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    private val coalesceThreshold: Int = DEFAULT_COALESCE_THRESHOLD,
    private val coalesceMinChunks: Int = DEFAULT_COALESCE_MIN_CHUNKS,
) : StreamProcessor {
    private val chunks = ArrayDeque<ReadBuffer>()
    private var totalAvailable = 0

    // Coalescing state: small appends are copied into a tail buffer that is NOT in chunks.
    // Invariant: totalAvailable == sum(chunks[i].remaining()) + coalesceWritten
    private var coalesceTail: ReadWriteBuffer? = null
    private var coalesceWritten = 0

    // Peek cache: remembers the last peekByte scan position so sequential peeks
    // (e.g., peekInt calling peekByte 4 times at offset, offset+1, offset+2, offset+3)
    // avoid rescanning from the beginning. Invalidated on any consume operation.
    private var peekCacheChunkIdx = 0
    private var peekCacheCumulative = 0 // cumulative bytes before the cached chunk

    companion object {
        /** Default threshold: buffers smaller than this are eligible for coalescing when chunks accumulate. */
        internal const val DEFAULT_COALESCE_THRESHOLD = 256

        /** Default minimum chunk count before coalescing engages. */
        internal const val DEFAULT_COALESCE_MIN_CHUNKS = 8
    }

    private fun assembleShort(
        b0: Int,
        b1: Int,
    ): Short =
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            ((b0 shl 8) or b1).toShort()
        } else {
            ((b1 shl 8) or b0).toShort()
        }

    private fun assembleInt(
        b0: Int,
        b1: Int,
        b2: Int,
        b3: Int,
    ): Int =
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        } else {
            (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
        }

    private fun assembleLong(
        b0: Int,
        b1: Int,
        b2: Int,
        b3: Int,
        b4: Int,
        b5: Int,
        b6: Int,
        b7: Int,
    ): Long =
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            (b0.toLong() shl 56) or (b1.toLong() shl 48) or (b2.toLong() shl 40) or (b3.toLong() shl 32) or
                (b4.toLong() shl 24) or (b5.toLong() shl 16) or (b6.toLong() shl 8) or b7.toLong()
        } else {
            (b7.toLong() shl 56) or (b6.toLong() shl 48) or (b5.toLong() shl 40) or (b4.toLong() shl 32) or
                (b3.toLong() shl 24) or (b2.toLong() shl 16) or (b1.toLong() shl 8) or b0.toLong()
        }

    override fun append(chunk: ReadBuffer) {
        val size = chunk.remaining()
        if (size <= 0) return

        // Coalesce small chunks only when they are accumulating in the deque.
        // When data is consumed immediately after append (protocol parsing),
        // chunks.size stays low and we take the zero-copy path.
        if (size < coalesceThreshold && (coalesceTail != null || chunks.size >= coalesceMinChunks)) {
            var tail = coalesceTail
            if (tail == null || (tail.capacity - coalesceWritten) < size) {
                sealCoalesceBuffer()
                tail = pool.acquire(maxOf(coalesceThreshold, size))
                coalesceTail = tail
                coalesceWritten = 0
            }

            tail.position(coalesceWritten)
            tail.write(chunk)
            coalesceWritten = tail.position()
            totalAvailable += size

            // We copied the data — free the original small chunk
            freeConsumedChunk(chunk)
            return
        }

        // Large chunk or chunks not yet accumulating — zero-copy add to deque
        sealCoalesceBuffer()
        chunks.addLast(chunk)
        totalAvailable += size
    }

    /**
     * Seals the coalesce tail buffer and moves it into the chunks deque.
     * After this call, [coalesceTail] is null and [coalesceWritten] is 0.
     * [totalAvailable] is NOT modified (bytes were already counted on append).
     */
    private fun sealCoalesceBuffer() {
        val tail = coalesceTail ?: return
        if (coalesceWritten > 0) {
            tail.position(0)
            tail.setLimit(coalesceWritten)
            chunks.addLast(tail)
        } else {
            freeConsumedChunk(tail)
        }
        coalesceTail = null
        coalesceWritten = 0
    }

    /**
     * Ensures the chunks deque contains at least [needed] bytes.
     * Seals the coalesce buffer if data from it is required.
     */
    private fun ensureChunksContain(needed: Int) {
        if (coalesceTail != null) {
            val chunksAvailable = totalAvailable - coalesceWritten
            if (chunksAvailable < needed) {
                sealCoalesceBuffer()
            }
        }
    }

    override fun available(): Int = totalAvailable

    override fun peekByte(offset: Int): Byte {
        require(totalAvailable > offset) { "Not enough data: need ${offset + 1}, have $totalAvailable" }

        // Use cached scan position if the offset is at or after where we last looked
        var chunkIdx: Int
        var cumulative: Int
        if (offset >= peekCacheCumulative && peekCacheChunkIdx < chunks.size) {
            chunkIdx = peekCacheChunkIdx
            cumulative = peekCacheCumulative
        } else {
            chunkIdx = 0
            cumulative = 0
        }

        while (chunkIdx < chunks.size) {
            val chunk = chunks[chunkIdx]
            val chunkRemaining = chunk.remaining()
            if (offset - cumulative < chunkRemaining) {
                peekCacheChunkIdx = chunkIdx
                peekCacheCumulative = cumulative
                return chunk.get(chunk.position() + (offset - cumulative))
            }
            cumulative += chunkRemaining
            chunkIdx++
        }

        // Check coalesce tail (data not yet sealed into chunks)
        val tail = coalesceTail
        if (tail != null && (offset - cumulative) < coalesceWritten) {
            return tail.get(offset - cumulative)
        }
        throw IllegalStateException("Unexpected end of data")
    }

    private fun invalidatePeekCache() {
        peekCacheChunkIdx = 0
        peekCacheCumulative = 0
    }

    override fun peekShort(offset: Int): Short {
        require(totalAvailable >= offset + Short.SIZE_BYTES) { "Not enough data for Short at offset $offset" }

        val chunk = chunks.firstOrNull()
        if (chunk != null && chunk.remaining() >= offset + Short.SIZE_BYTES && chunk.byteOrder == byteOrder) {
            return chunk.getShort(chunk.position() + offset)
        }

        return assembleShort(
            peekByte(offset).toInt() and 0xFF,
            peekByte(offset + 1).toInt() and 0xFF,
        )
    }

    override fun peekInt(offset: Int): Int {
        require(totalAvailable >= offset + Int.SIZE_BYTES) { "Not enough data for Int at offset $offset" }

        val chunk = chunks.firstOrNull()
        if (chunk != null && chunk.remaining() >= offset + Int.SIZE_BYTES && chunk.byteOrder == byteOrder) {
            return chunk.getInt(chunk.position() + offset)
        }

        return assembleInt(
            peekByte(offset).toInt() and 0xFF,
            peekByte(offset + 1).toInt() and 0xFF,
            peekByte(offset + 2).toInt() and 0xFF,
            peekByte(offset + 3).toInt() and 0xFF,
        )
    }

    override fun peekLong(offset: Int): Long {
        require(totalAvailable >= offset + Long.SIZE_BYTES) { "Not enough data for Long at offset $offset" }

        val chunk = chunks.firstOrNull()
        if (chunk != null && chunk.remaining() >= offset + Long.SIZE_BYTES && chunk.byteOrder == byteOrder) {
            return chunk.getLong(chunk.position() + offset)
        }

        return assembleLong(
            peekByte(offset).toInt() and 0xFF,
            peekByte(offset + 1).toInt() and 0xFF,
            peekByte(offset + 2).toInt() and 0xFF,
            peekByte(offset + 3).toInt() and 0xFF,
            peekByte(offset + 4).toInt() and 0xFF,
            peekByte(offset + 5).toInt() and 0xFF,
            peekByte(offset + 6).toInt() and 0xFF,
            peekByte(offset + 7).toInt() and 0xFF,
        )
    }

    override fun peekMismatch(pattern: ReadBuffer): Int {
        val patternSize = pattern.remaining()
        if (totalAvailable < patternSize) return minOf(totalAvailable, patternSize)
        if (patternSize == 0) return -1

        val patternPos = pattern.position()
        val firstChunk = chunks.firstOrNull()

        // Fast path: pattern fits entirely in first chunk - use primitive comparisons
        if (firstChunk != null && firstChunk.remaining() >= patternSize) {
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
        invalidatePeekCache()
        ensureChunksContain(1)
        val chunk = chunks.first()
        val byte = chunk.readByte()
        totalAvailable--
        removeChunkIfEmpty(chunk)
        return byte
    }

    override fun readUnsignedByte(): Int = readByte().toInt() and 0xFF

    override fun readShort(): Short {
        require(totalAvailable >= Short.SIZE_BYTES) { "Not enough data for Short" }
        invalidatePeekCache()
        ensureChunksContain(Short.SIZE_BYTES)
        val chunk = chunks.first()
        if (chunk.remaining() >= Short.SIZE_BYTES && chunk.byteOrder == byteOrder) {
            val value = chunk.readShort()
            totalAvailable -= Short.SIZE_BYTES
            removeChunkIfEmpty(chunk)
            return value
        }
        return assembleShort(
            readByte().toInt() and 0xFF,
            readByte().toInt() and 0xFF,
        )
    }

    override fun readInt(): Int {
        require(totalAvailable >= Int.SIZE_BYTES) { "Not enough data for Int" }
        invalidatePeekCache()
        ensureChunksContain(Int.SIZE_BYTES)
        val chunk = chunks.first()
        if (chunk.remaining() >= Int.SIZE_BYTES && chunk.byteOrder == byteOrder) {
            val value = chunk.readInt()
            totalAvailable -= Int.SIZE_BYTES
            removeChunkIfEmpty(chunk)
            return value
        }
        return assembleInt(
            readByte().toInt() and 0xFF,
            readByte().toInt() and 0xFF,
            readByte().toInt() and 0xFF,
            readByte().toInt() and 0xFF,
        )
    }

    override fun readLong(): Long {
        require(totalAvailable >= Long.SIZE_BYTES) { "Not enough data for Long" }
        invalidatePeekCache()
        ensureChunksContain(Long.SIZE_BYTES)
        val chunk = chunks.first()
        if (chunk.remaining() >= Long.SIZE_BYTES && chunk.byteOrder == byteOrder) {
            val value = chunk.readLong()
            totalAvailable -= Long.SIZE_BYTES
            removeChunkIfEmpty(chunk)
            return value
        }
        return assembleLong(
            readByte().toInt() and 0xFF,
            readByte().toInt() and 0xFF,
            readByte().toInt() and 0xFF,
            readByte().toInt() and 0xFF,
            readByte().toInt() and 0xFF,
            readByte().toInt() and 0xFF,
            readByte().toInt() and 0xFF,
            readByte().toInt() and 0xFF,
        )
    }

    /**
     * Reads a buffer of exactly [size] bytes.
     *
     * The returned buffer has [ReadBuffer.remaining] == [size] and is ready to read
     * from its current position. **Do not assume position is 0** — the buffer may be
     * a view/slice of a larger chunk where position > 0 is correct.
     *
     * Returns the chunk directly when data fits exactly, or a slice when contiguous.
     * Copies when data spans multiple chunks.
     *
     * The returned buffer is not released back to the pool. For proper pool recycling,
     * prefer [readBufferScoped].
     */
    override fun readBuffer(size: Int): ReadBuffer {
        require(totalAvailable >= size) { "Not enough data: need $size, have $totalAvailable" }
        invalidatePeekCache()
        ensureChunksContain(size)
        require(chunks.isNotEmpty() || size == 0) { "No chunks available" }

        val chunk = chunks.firstOrNull()
        if (chunk == null || size == 0) {
            // Empty read - return empty slice from pool
            val empty = pool.acquire(0)
            empty.resetForRead()
            return empty
        }
        if (chunk.remaining() == size) {
            chunks.removeFirst()
            totalAvailable -= size
            // If position > 0, the chunk may contain framing bytes (e.g. WebSocket headers)
            // before the current position. Slice to ensure position 0 = start of payload,
            // so resetForRead() can't expose those bytes to the caller.
            if (chunk.position() == 0) {
                return chunk // already clean — zero-copy transfer
            }
            return chunk.slice()
        }
        if (chunk.remaining() > size) {
            // Data is contiguous, return a slice
            val oldLimit = chunk.limit()
            chunk.setLimit(chunk.position() + size)
            val slice = chunk.slice()
            chunk.setLimit(oldLimit)
            chunk.position(chunk.position() + size)
            totalAvailable -= size
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

    override fun <T> readBufferScoped(
        size: Int,
        block: ReadBuffer.() -> T,
    ): T {
        require(totalAvailable >= size) { "Not enough data: need $size, have $totalAvailable" }
        invalidatePeekCache()
        ensureChunksContain(size)
        require(chunks.isNotEmpty() || size == 0) { "No chunks available" }

        val chunk = chunks.firstOrNull()
        if (chunk == null || size == 0) {
            val empty = pool.acquire(0)
            empty.resetForRead()
            return try {
                block(empty)
            } finally {
                freeConsumedChunk(empty)
            }
        }
        if (chunk.remaining() == size) {
            // Exact match: remove chunk, pass to block, then free
            chunks.removeFirst()
            totalAvailable -= size
            return try {
                block(chunk)
            } finally {
                freeConsumedChunk(chunk)
            }
        }
        if (chunk.remaining() > size) {
            // Partial: slice without removing chunk (chunk stays in deque)
            val oldLimit = chunk.limit()
            chunk.setLimit(chunk.position() + size)
            val slice = chunk.slice()
            chunk.setLimit(oldLimit)
            chunk.position(chunk.position() + size)
            totalAvailable -= size
            return block(slice)
        }

        // Multi-chunk: copy into pooled buffer, pass to block, then free
        val merged = pool.acquire(size)
        var remaining = size
        while (remaining > 0 && chunks.isNotEmpty()) {
            val currentChunk = chunks.first()
            val toCopy = minOf(remaining, currentChunk.remaining())
            val oldLimit = currentChunk.limit()
            currentChunk.setLimit(currentChunk.position() + toCopy)
            merged.write(currentChunk)
            currentChunk.setLimit(oldLimit)
            remaining -= toCopy
            totalAvailable -= toCopy
            removeChunkIfEmpty(currentChunk)
        }
        merged.resetForRead()
        return try {
            block(merged)
        } finally {
            freeConsumedChunk(merged)
        }
    }

    override fun skip(count: Int) {
        require(totalAvailable >= count) { "Not enough data to skip: need $count, have $totalAvailable" }
        invalidatePeekCache()
        ensureChunksContain(count)
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
        invalidatePeekCache()
        val tail = coalesceTail
        if (tail != null) {
            freeConsumedChunk(tail)
            coalesceTail = null
            coalesceWritten = 0
        }
        for (chunk in chunks) {
            freeConsumedChunk(chunk)
        }
        chunks.clear()
        totalAvailable = 0
    }

    private fun removeChunkIfEmpty(chunk: ReadBuffer) {
        if (chunk.remaining() == 0) {
            chunks.removeFirst()
            freeConsumedChunk(chunk)
        }
    }

    private fun freeConsumedChunk(buffer: ReadBuffer) {
        // Release consumed chunks. For PooledBuffer, freeNativeMemory() decrements
        // refCount — the buffer is only returned to pool when all slices are also freed.
        (buffer as? PlatformBuffer)?.freeNativeMemory()
    }
}
