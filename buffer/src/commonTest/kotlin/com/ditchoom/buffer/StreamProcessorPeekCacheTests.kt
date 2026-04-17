package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for the StreamProcessor peek cache.
 *
 * The peek cache remembers the last peekByte scan position so sequential
 * peeks avoid rescanning from the beginning. These tests validate:
 * - Cache correctness for sequential, backward, and random access patterns
 * - Cache invalidation on every consuming operation
 * - Interaction with append (cache must remain valid after appends)
 * - Interaction with partial chunk consumption
 * - Interaction with chunk removal
 * - Multi-byte peek (peekShort/Int/Long) using the cache internally
 * - Edge cases: single chunk, single byte, empty after consume
 */
class StreamProcessorPeekCacheTests {
    private fun makeProcessor(pool: BufferPool = BufferPool(defaultBufferSize = 1024)): StreamProcessor = StreamProcessor.create(pool)

    private fun appendBytes(
        processor: StreamProcessor,
        vararg bytes: Byte,
    ) {
        val buf = BufferFactory.managed().allocate(bytes.size)
        for (b in bytes) buf.writeByte(b)
        buf.resetForRead()
        processor.append(buf)
    }

    private fun appendChunk(
        processor: StreamProcessor,
        size: Int,
        fill: Byte,
    ) {
        val buf = BufferFactory.managed().allocate(size)
        for (i in 0 until size) buf.writeByte(fill)
        buf.resetForRead()
        processor.append(buf)
    }

    private fun appendSequential(
        processor: StreamProcessor,
        size: Int,
        startByte: Int = 0,
    ) {
        val buf = BufferFactory.managed().allocate(size)
        for (i in 0 until size) buf.writeByte(((startByte + i) and 0xFF).toByte())
        buf.resetForRead()
        processor.append(buf)
    }

    // ============================================================================
    // Sequential Forward Peeks (cache hit path)
    // ============================================================================

    @Test
    fun sequentialPeekByteUsesCache() {
        val processor = makeProcessor()
        // 3 chunks of 4 bytes each: [0,1,2,3] [4,5,6,7] [8,9,10,11]
        appendSequential(processor, 4, startByte = 0)
        appendSequential(processor, 4, startByte = 4)
        appendSequential(processor, 4, startByte = 8)

        // Sequential peeks across chunk boundaries — cache should accelerate
        for (i in 0 until 12) {
            assertEquals(i.toByte(), processor.peekByte(i), "peekByte($i) mismatch")
        }
        // Repeat to confirm cache doesn't corrupt
        for (i in 0 until 12) {
            assertEquals(i.toByte(), processor.peekByte(i), "repeat peekByte($i) mismatch")
        }
    }

    @Test
    fun peekIntUsesCache4SequentialPeekBytes() {
        val processor = makeProcessor()
        // Two chunks: [0x11,0x22] [0x33,0x44]
        appendBytes(processor, 0x11, 0x22)
        appendBytes(processor, 0x33, 0x44)

        // peekInt calls peekByte 4 times (offset, offset+1, offset+2, offset+3)
        // when data spans chunks. The cache makes calls 2-4 O(1).
        assertEquals(0x11223344, processor.peekInt(0))
    }

    @Test
    fun peekLongAcross8SingleByteChunks() {
        val processor = makeProcessor()
        // 8 single-byte chunks — worst case for scanning without cache
        for (i in 1..8) appendBytes(processor, i.toByte())

        // peekLong calls peekByte 8 times; cache makes 7 of them O(1)
        assertEquals(0x0102030405060708L, processor.peekLong(0))
    }

    @Test
    fun peekShortAtVariousOffsets() {
        val processor = makeProcessor()
        appendSequential(processor, 10, startByte = 0)

        assertEquals((0x00_01).toShort(), processor.peekShort(0))
        assertEquals((0x02_03).toShort(), processor.peekShort(2))
        assertEquals((0x04_05).toShort(), processor.peekShort(4))
        assertEquals((0x08_09).toShort(), processor.peekShort(8))
    }

    // ============================================================================
    // Backward Peeks (cache reset path)
    // ============================================================================

    @Test
    fun backwardPeekResetsCacheAndScansFromStart() {
        val processor = makeProcessor()
        appendSequential(processor, 4, startByte = 0)
        appendSequential(processor, 4, startByte = 4)
        appendSequential(processor, 4, startByte = 8)

        // Forward peek builds cache
        assertEquals(10.toByte(), processor.peekByte(10))
        // Backward peek must reset cache and rescan
        assertEquals(2.toByte(), processor.peekByte(2))
        // Forward again from new cache position
        assertEquals(3.toByte(), processor.peekByte(3))
    }

    @Test
    fun alternatingForwardBackwardPeeks() {
        val processor = makeProcessor()
        appendSequential(processor, 20, startByte = 0)

        // Zigzag pattern: forward, back, forward further, back
        assertEquals(15.toByte(), processor.peekByte(15))
        assertEquals(3.toByte(), processor.peekByte(3))
        assertEquals(18.toByte(), processor.peekByte(18))
        assertEquals(0.toByte(), processor.peekByte(0))
        assertEquals(19.toByte(), processor.peekByte(19))
    }

    // ============================================================================
    // Cache Invalidation on Consuming Operations
    // ============================================================================

    @Test
    fun readByteInvalidatesCache() {
        val processor = makeProcessor()
        appendSequential(processor, 10, startByte = 0)

        // Build cache at offset 8
        assertEquals(8.toByte(), processor.peekByte(8))
        // Consume 1 byte — cache must be invalidated
        assertEquals(0.toByte(), processor.readByte())
        // Now offset 8 in the remaining data is byte 9
        assertEquals(8.toByte(), processor.peekByte(7))
        assertEquals(9.toByte(), processor.peekByte(8))
    }

    @Test
    fun readShortInvalidatesCache() {
        val processor = makeProcessor()
        appendSequential(processor, 10, startByte = 0)

        assertEquals(8.toByte(), processor.peekByte(8))
        processor.readShort() // consumes bytes 0,1
        assertEquals(8, processor.available())
        assertEquals(2.toByte(), processor.peekByte(0))
        assertEquals(9.toByte(), processor.peekByte(7))
    }

    @Test
    fun readIntInvalidatesCache() {
        val processor = makeProcessor()
        appendSequential(processor, 10, startByte = 0)

        assertEquals(9.toByte(), processor.peekByte(9))
        processor.readInt() // consumes bytes 0-3
        assertEquals(6, processor.available())
        assertEquals(4.toByte(), processor.peekByte(0))
    }

    @Test
    fun readLongInvalidatesCache() {
        val processor = makeProcessor()
        appendSequential(processor, 16, startByte = 0)

        assertEquals(15.toByte(), processor.peekByte(15))
        processor.readLong() // consumes bytes 0-7
        assertEquals(8, processor.available())
        assertEquals(8.toByte(), processor.peekByte(0))
    }

    @Test
    fun skipInvalidatesCache() {
        val processor = makeProcessor()
        appendSequential(processor, 10, startByte = 0)

        assertEquals(7.toByte(), processor.peekByte(7))
        processor.skip(5) // skip bytes 0-4
        assertEquals(5, processor.available())
        assertEquals(5.toByte(), processor.peekByte(0))
        assertEquals(9.toByte(), processor.peekByte(4))
    }

    @Test
    fun readBufferInvalidatesCache() {
        val processor = makeProcessor()
        appendSequential(processor, 10, startByte = 0)

        assertEquals(9.toByte(), processor.peekByte(9))
        val buf = processor.readBuffer(6) // consumes bytes 0-5
        assertEquals(6, buf.remaining())
        assertEquals(4, processor.available())
        assertEquals(6.toByte(), processor.peekByte(0))
    }

    @Test
    fun readBufferScopedInvalidatesCache() {
        val processor = makeProcessor()
        appendSequential(processor, 10, startByte = 0)

        assertEquals(9.toByte(), processor.peekByte(9))
        val firstByte = processor.readBufferScoped(3) { readByte() }
        assertEquals(0.toByte(), firstByte)
        assertEquals(7, processor.available())
        assertEquals(3.toByte(), processor.peekByte(0))
    }

    // ============================================================================
    // Cache Survives Append (append only adds to end)
    // ============================================================================

    @Test
    fun appendDoesNotInvalidateCache() {
        val processor = makeProcessor()
        appendSequential(processor, 4, startByte = 0)
        appendSequential(processor, 4, startByte = 4)

        // Build cache pointing into second chunk
        assertEquals(6.toByte(), processor.peekByte(6))

        // Append a third chunk — cache should still be valid
        appendSequential(processor, 4, startByte = 8)

        // Peek forward into new chunk using cache (doesn't rescan from 0)
        assertEquals(7.toByte(), processor.peekByte(7))
        assertEquals(8.toByte(), processor.peekByte(8))
        assertEquals(11.toByte(), processor.peekByte(11))
    }

    @Test
    fun peekAfterMultipleAppends() {
        val processor = makeProcessor()

        // Append chunks incrementally, peeking after each
        for (round in 0 until 10) {
            appendSequential(processor, 8, startByte = round * 8)
            val totalBytes = (round + 1) * 8
            // Peek at last byte of all data
            assertEquals(
                ((totalBytes - 1) and 0xFF).toByte(),
                processor.peekByte(totalBytes - 1),
                "Round $round: last byte mismatch",
            )
        }

        // Verify all bytes
        for (i in 0 until 80) {
            assertEquals((i and 0xFF).toByte(), processor.peekByte(i), "Full scan mismatch at $i")
        }
    }

    // ============================================================================
    // Partial Chunk Consumption + Cache
    // ============================================================================

    @Test
    fun partialChunkConsumptionThenPeek() {
        val processor = makeProcessor()
        // Single 10-byte chunk: [0,1,2,3,4,5,6,7,8,9]
        appendSequential(processor, 10, startByte = 0)

        // Peek at offset 8 — builds cache
        assertEquals(8.toByte(), processor.peekByte(8))

        // Consume 3 bytes (partial chunk consumption)
        processor.readByte() // 0
        processor.readByte() // 1
        processor.readByte() // 2

        // Cache was invalidated by each readByte
        assertEquals(7, processor.available())
        assertEquals(3.toByte(), processor.peekByte(0))
        assertEquals(9.toByte(), processor.peekByte(6))
    }

    @Test
    fun consumeEntireFirstChunkThenPeek() {
        val processor = makeProcessor()
        appendSequential(processor, 4, startByte = 0)
        appendSequential(processor, 4, startByte = 4)

        // Build cache in second chunk
        assertEquals(6.toByte(), processor.peekByte(6))

        // Consume entire first chunk
        processor.skip(4)

        // Cache invalidated — now byte 0 is old byte 4
        assertEquals(4, processor.available())
        assertEquals(4.toByte(), processor.peekByte(0))
        assertEquals(7.toByte(), processor.peekByte(3))
    }

    // ============================================================================
    // Interleaved Peek + Read + Append (protocol parsing pattern)
    // ============================================================================

    @Test
    fun interleavedPeekReadAppend() {
        val processor = makeProcessor()

        // Simulate protocol: append frame, peek header, read payload, repeat
        for (frame in 0 until 20) {
            // Append a 8-byte frame: [frameId, 0, 0, 0, payload x4]
            val buf = BufferFactory.managed().allocate(8)
            buf.writeByte(frame.toByte()) // frame ID
            buf.writeByte(0)
            buf.writeByte(0)
            buf.writeByte(4) // payload length = 4
            for (j in 0 until 4) buf.writeByte(((frame * 10 + j) and 0xFF).toByte())
            buf.resetForRead()
            processor.append(buf)

            // Peek header
            val frameId = processor.peekByte(0)
            assertEquals(frame.toByte(), frameId)
            val payloadLen = processor.peekInt(0) and 0xFF // last byte of int = 4

            // Read header
            processor.skip(4)

            // Read payload
            val payload = processor.readBuffer(payloadLen)
            assertEquals(4, payload.remaining())
            assertEquals(((frame * 10) and 0xFF).toByte(), payload.readByte())

            assertEquals(0, processor.available())
        }
    }

    // ============================================================================
    // Many Small Chunks (cache across chunk boundaries)
    // ============================================================================

    @Test
    fun peekAcrossManySmallChunks() {
        val processor = makeProcessor()
        // 100 single-byte chunks
        for (i in 0 until 100) {
            appendBytes(processor, (i and 0xFF).toByte())
        }

        // Forward scan — cache makes each step O(1) after the first
        for (i in 0 until 100) {
            assertEquals((i and 0xFF).toByte(), processor.peekByte(i), "Forward peek at $i")
        }

        // Backward scan — each peek resets cache
        for (i in 99 downTo 0) {
            assertEquals((i and 0xFF).toByte(), processor.peekByte(i), "Backward peek at $i")
        }
    }

    @Test
    fun peekIntAcrossManySmallChunksAtVariousOffsets() {
        val processor = makeProcessor()
        // 40 single-byte chunks: 0x00..0x27
        for (i in 0 until 40) appendBytes(processor, i.toByte())

        // peekInt at offset 0: bytes 0,1,2,3
        assertEquals(0x00010203, processor.peekInt(0))

        // peekInt at offset 10: bytes 10,11,12,13
        assertEquals(0x0A0B0C0D, processor.peekInt(10))

        // peekInt at offset 36: bytes 36,37,38,39
        assertEquals(0x24252627, processor.peekInt(36))
    }

    // ============================================================================
    // Edge Cases
    // ============================================================================

    @Test
    fun singleByteChunkPeek() {
        val processor = makeProcessor()
        appendBytes(processor, 0x42)

        assertEquals(0x42.toByte(), processor.peekByte(0))
        assertEquals(0x42.toByte(), processor.peekByte(0)) // repeat
    }

    @Test
    fun peekAtOffset0AlwaysWorks() {
        val processor = makeProcessor()
        appendBytes(processor, 0xAA.toByte(), 0xBB.toByte())

        assertEquals(0xAA.toByte(), processor.peekByte(0))
        // Peek at end then back to 0
        assertEquals(0xBB.toByte(), processor.peekByte(1))
        assertEquals(0xAA.toByte(), processor.peekByte(0))
    }

    @Test
    fun peekAtLastByte() {
        val processor = makeProcessor()
        appendSequential(processor, 100, startByte = 0)

        assertEquals(99.toByte(), processor.peekByte(99))
        // Peek at 0 then back to last
        assertEquals(0.toByte(), processor.peekByte(0))
        assertEquals(99.toByte(), processor.peekByte(99))
    }

    @Test
    fun peekBeyondAvailableThrows() {
        val processor = makeProcessor()
        appendBytes(processor, 0x01, 0x02, 0x03)

        assertFailsWith<IllegalArgumentException> {
            processor.peekByte(3) // only 3 bytes, offset 3 is out of bounds
        }
    }

    @Test
    fun peekAfterConsumeAllAndReAppend() {
        val processor = makeProcessor()
        appendSequential(processor, 4, startByte = 0)

        // Build cache
        assertEquals(3.toByte(), processor.peekByte(3))

        // Consume everything
        processor.skip(4)
        assertEquals(0, processor.available())

        // Append new data
        appendSequential(processor, 4, startByte = 10)

        // Peek should see the new data, not stale cache
        assertEquals(10.toByte(), processor.peekByte(0))
        assertEquals(13.toByte(), processor.peekByte(3))
    }

    @Test
    fun peekAfterReleaseAndReuse() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val processor = StreamProcessor.create(pool)
        appendSequential(processor, 8, startByte = 0)

        assertEquals(7.toByte(), processor.peekByte(7))
        processor.release()

        // Create new processor with same pool
        val processor2 = StreamProcessor.create(pool)
        appendSequential(processor2, 8, startByte = 20)

        assertEquals(20.toByte(), processor2.peekByte(0))
        assertEquals(27.toByte(), processor2.peekByte(7))
        processor2.release()
        pool.clear()
    }

    // ============================================================================
    // Cache + readBufferScoped (common protocol pattern)
    // ============================================================================

    @Test
    fun peekThenReadBufferScopedRepeated() {
        val processor = makeProcessor()

        // 5 frames: 4-byte header + 4-byte payload
        for (frame in 0 until 5) {
            val buf = BufferFactory.managed().allocate(8)
            buf.writeInt(4) // payload length
            buf.writeInt(frame * 100) // payload
            buf.resetForRead()
            processor.append(buf)
        }

        // Parse all frames using peek + readBufferScoped
        for (frame in 0 until 5) {
            val len = processor.peekInt(0)
            assertEquals(4, len)
            processor.skip(4)
            val value = processor.readBufferScoped(4) { readInt() }
            assertEquals(frame * 100, value)
        }
        assertEquals(0, processor.available())
    }

    // ============================================================================
    // Cache with peekMismatch / peekMatches
    // ============================================================================

    @Test
    fun peekMismatchDoesNotCorruptCache() {
        val processor = makeProcessor()
        appendBytes(processor, 0x01, 0x02, 0x03, 0x04)
        appendBytes(processor, 0x05, 0x06, 0x07, 0x08)

        // Build cache
        assertEquals(0x07.toByte(), processor.peekByte(6))

        // peekMismatch reads bytes but shouldn't affect the cache
        // (peekMismatch has its own scan path, not through peekByte for fast path)
        val pattern = BufferFactory.managed().allocate(4)
        pattern.writeByte(0x01)
        pattern.writeByte(0x02)
        pattern.writeByte(0x03)
        pattern.writeByte(0x04)
        pattern.resetForRead()
        assertEquals(-1, processor.peekMismatch(pattern))

        // Cache should still work for peekByte
        assertEquals(0x07.toByte(), processor.peekByte(6))
        assertEquals(0x08.toByte(), processor.peekByte(7))
    }

    // ============================================================================
    // Stress: many consume-peek cycles
    // ============================================================================

    @Test
    fun stressManyConsumePeekCycles() {
        val processor = makeProcessor()

        // Append 1000 bytes across 100 chunks of 10 bytes
        for (i in 0 until 100) {
            appendSequential(processor, 10, startByte = i * 10)
        }

        // Consume 1 byte, peek at various offsets, repeat
        for (consumed in 0 until 500) {
            val remaining = 1000 - consumed
            assertEquals(remaining, processor.available())

            // Peek at offset 0
            assertEquals(((consumed) and 0xFF).toByte(), processor.peekByte(0))

            // Peek at middle
            if (remaining > 50) {
                assertEquals(((consumed + 50) and 0xFF).toByte(), processor.peekByte(50))
            }

            // Peek at last byte
            assertEquals(((999) and 0xFF).toByte(), processor.peekByte(remaining - 1))

            processor.readByte()
        }

        assertEquals(500, processor.available())
        processor.release()
    }
}
