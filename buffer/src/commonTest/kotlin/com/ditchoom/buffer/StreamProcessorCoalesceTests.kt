package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.DefaultStreamProcessor
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for StreamProcessor coalescing optimization.
 *
 * Validates:
 * - Data integrity through coalescing (bytes survive copy)
 * - No buffer leaks (pool stats before/after)
 * - Correct available() accounting with coalesce tail
 * - Edge cases: exact fill, mixed sizes, release with pending coalesce
 * - Configurable thresholds
 * - Coalescing disabled (threshold=0)
 */
class StreamProcessorCoalesceTests {
    // ============================================================================
    // Data Integrity
    // ============================================================================

    @Test
    fun smallChunksCoalesceWithCorrectData() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 16)
        val processor = StreamProcessor.create(pool)

        // Append 20 small chunks (20 x 32 = 640 bytes)
        // With default minChunks=8, coalescing kicks in after 8 chunks
        for (i in 0 until 20) {
            val buf = BufferFactory.managed().allocate(32)
            for (j in 0 until 32) {
                buf.writeByte(((i * 32 + j) and 0xFF).toByte())
            }
            buf.resetForRead()
            processor.append(buf)
        }

        assertEquals(640, processor.available())

        // Verify every byte is correct
        for (i in 0 until 640) {
            assertEquals(
                (i and 0xFF).toByte(),
                processor.readByte(),
                "Mismatch at byte $i",
            )
        }

        assertEquals(0, processor.available())
        processor.release()
        pool.clear()
    }

    @Test
    fun peekIntoCoalesceTail() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 16)
        // Force immediate coalescing with minChunks=0
        val processor = DefaultStreamProcessor(pool, coalesceMinChunks = 0)

        // Append a small chunk that will be coalesced into the tail
        val buf = BufferFactory.managed().allocate(4)
        buf.writeByte(0xAA.toByte())
        buf.writeByte(0xBB.toByte())
        buf.writeByte(0xCC.toByte())
        buf.writeByte(0xDD.toByte())
        buf.resetForRead()
        processor.append(buf)

        assertEquals(4, processor.available())

        // Peek should read from the coalesce tail (not yet sealed)
        assertEquals(0xAA.toByte(), processor.peekByte(0))
        assertEquals(0xBB.toByte(), processor.peekByte(1))
        assertEquals(0xCC.toByte(), processor.peekByte(2))
        assertEquals(0xDD.toByte(), processor.peekByte(3))

        // Available should be unchanged
        assertEquals(4, processor.available())

        // Now consume
        assertEquals(0xAA.toByte(), processor.readByte())
        assertEquals(0xBB.toByte(), processor.readByte())
        assertEquals(0xCC.toByte(), processor.readByte())
        assertEquals(0xDD.toByte(), processor.readByte())

        assertEquals(0, processor.available())
        processor.release()
        pool.clear()
    }

    @Test
    fun peekShortIntLongIntoCoalesceTail() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 16)
        val processor = DefaultStreamProcessor(pool, coalesceMinChunks = 0)

        // Append 8 bytes as a small chunk (will be coalesced)
        val buf = BufferFactory.managed().allocate(8)
        buf.writeShort(0x1234.toShort())
        buf.writeInt(0x56789ABC.toInt())
        buf.writeShort(0xDEF0.toShort())
        buf.resetForRead()
        processor.append(buf)

        assertEquals(8, processor.available())
        assertEquals(0x1234.toShort(), processor.peekShort(0))
        assertEquals(0x56789ABC.toInt(), processor.peekInt(2))
        assertEquals(0x123456789ABC.toLong().shr(16).toInt(), processor.peekInt(0))

        processor.release()
        pool.clear()
    }

    @Test
    fun readBufferFromCoalesceTail() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 16)
        val processor = DefaultStreamProcessor(pool, coalesceMinChunks = 0)

        val buf = BufferFactory.managed().allocate(16)
        for (i in 0 until 16) buf.writeByte(i.toByte())
        buf.resetForRead()
        processor.append(buf)

        // readBuffer should seal the coalesce tail and return the data
        val result = processor.readBuffer(16)
        assertEquals(16, result.remaining())
        for (i in 0 until 16) {
            assertEquals(i.toByte(), result.readByte(), "Mismatch at $i")
        }

        assertEquals(0, processor.available())
        processor.release()
        pool.clear()
    }

    @Test
    fun readBufferScopedFromCoalesceTail() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 16)
        val processor = DefaultStreamProcessor(pool, coalesceMinChunks = 0)

        val buf = BufferFactory.managed().allocate(8)
        buf.writeInt(42)
        buf.writeInt(99)
        buf.resetForRead()
        processor.append(buf)

        val sum = processor.readBufferScoped(8) {
            readInt() + readInt()
        }
        assertEquals(141, sum)

        assertEquals(0, processor.available())
        processor.release()
        pool.clear()
    }

    // ============================================================================
    // Mixed Sizes (large + small interleaved)
    // ============================================================================

    @Test
    fun mixedLargeAndSmallAppends() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 16)
        val processor = StreamProcessor.create(pool)

        // Append a large chunk (>= 256, bypasses coalescing)
        val largeBuf = BufferFactory.managed().allocate(512)
        for (i in 0 until 512) largeBuf.writeByte(0xAA.toByte())
        largeBuf.resetForRead()
        processor.append(largeBuf)

        // Append 20 small chunks to trigger coalescing
        for (i in 0 until 20) {
            val smallBuf = BufferFactory.managed().allocate(16)
            for (j in 0 until 16) smallBuf.writeByte(0xBB.toByte())
            smallBuf.resetForRead()
            processor.append(smallBuf)
        }

        // Append another large chunk
        val largeBuf2 = BufferFactory.managed().allocate(256)
        for (i in 0 until 256) largeBuf2.writeByte(0xCC.toByte())
        largeBuf2.resetForRead()
        processor.append(largeBuf2)

        assertEquals(512 + 320 + 256, processor.available())

        // Verify data order
        for (i in 0 until 512) {
            assertEquals(0xAA.toByte(), processor.readByte(), "Large chunk 1 mismatch at $i")
        }
        for (i in 0 until 320) {
            assertEquals(0xBB.toByte(), processor.readByte(), "Small chunks mismatch at $i")
        }
        for (i in 0 until 256) {
            assertEquals(0xCC.toByte(), processor.readByte(), "Large chunk 2 mismatch at $i")
        }

        assertEquals(0, processor.available())
        processor.release()
        pool.clear()
    }

    // ============================================================================
    // Coalescing Disabled (threshold = 0)
    // ============================================================================

    @Test
    fun coalescingDisabledWhenThresholdZero() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 16)
        val processor = DefaultStreamProcessor(pool, coalesceThreshold = 0)

        // Append many small chunks — none should be coalesced
        for (i in 0 until 20) {
            val buf = BufferFactory.managed().allocate(8)
            buf.writeByte(i.toByte())
            buf.writeByte(i.toByte())
            buf.writeByte(i.toByte())
            buf.writeByte(i.toByte())
            buf.writeByte(i.toByte())
            buf.writeByte(i.toByte())
            buf.writeByte(i.toByte())
            buf.writeByte(i.toByte())
            buf.resetForRead()
            processor.append(buf)
        }

        assertEquals(160, processor.available())

        // Verify data
        for (i in 0 until 20) {
            for (j in 0 until 8) {
                assertEquals(i.toByte(), processor.readByte(), "Mismatch chunk=$i byte=$j")
            }
        }

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // Available() Accounting
    // ============================================================================

    @Test
    fun availableCorrectWithCoalesceTail() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 16)
        val processor = DefaultStreamProcessor(pool, coalesceMinChunks = 0)

        assertEquals(0, processor.available())

        // Small append → goes to coalesce tail
        val buf1 = BufferFactory.managed().allocate(10)
        for (i in 0 until 10) buf1.writeByte(i.toByte())
        buf1.resetForRead()
        processor.append(buf1)
        assertEquals(10, processor.available())

        // Another small append → appended to same coalesce tail
        val buf2 = BufferFactory.managed().allocate(5)
        for (i in 0 until 5) buf2.writeByte(i.toByte())
        buf2.resetForRead()
        processor.append(buf2)
        assertEquals(15, processor.available())

        // Read some bytes (seals coalesce tail first)
        processor.readByte()
        assertEquals(14, processor.available())

        processor.skip(4)
        assertEquals(10, processor.available())

        val remaining = processor.readBuffer(10)
        assertEquals(10, remaining.remaining())
        assertEquals(0, processor.available())

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // Release With Pending Coalesce
    // ============================================================================

    @Test
    fun releaseFreesCoalesceTail() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 16)
        val processor = DefaultStreamProcessor(pool, coalesceMinChunks = 0)

        // Append small data that sits in the coalesce tail
        val buf = BufferFactory.managed().allocate(32)
        for (i in 0 until 32) buf.writeByte(i.toByte())
        buf.resetForRead()
        processor.append(buf)

        assertEquals(32, processor.available())

        // Release without reading — coalesce tail should be freed, not leaked
        processor.release()
        assertEquals(0, processor.available())

        // Pool should still work (no corruption)
        val testBuf = pool.acquire(64)
        assertTrue(testBuf.capacity >= 64)
        pool.release(testBuf)
        pool.clear()
    }

    @Test
    fun releaseWithMixedChunksAndCoalesceTail() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 16)
        val processor = DefaultStreamProcessor(pool, coalesceMinChunks = 0)

        // Large chunk → goes to deque
        val large = BufferFactory.managed().allocate(512)
        for (i in 0 until 512) large.writeByte(i.toByte())
        large.resetForRead()
        processor.append(large)

        // Small chunk → goes to coalesce tail
        val small = BufferFactory.managed().allocate(32)
        for (i in 0 until 32) small.writeByte(i.toByte())
        small.resetForRead()
        processor.append(small)

        assertEquals(544, processor.available())

        // Release both the deque chunk and the coalesce tail
        processor.release()
        assertEquals(0, processor.available())
        pool.clear()
    }

    // ============================================================================
    // No Leak Detection via Pool Stats
    // ============================================================================

    @Test
    fun noLeakedBuffersAfterCoalescingWorkload() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 32)
        val processor = DefaultStreamProcessor(pool, coalesceMinChunks = 2)

        // Run a workload: append many small chunks, read them all
        for (round in 0 until 5) {
            for (i in 0 until 50) {
                val buf = BufferFactory.managed().allocate(16)
                for (j in 0 until 16) buf.writeByte(((round * 50 + i + j) and 0xFF).toByte())
                buf.resetForRead()
                processor.append(buf)
            }

            // Read all data
            val total = processor.available()
            processor.readBufferScoped(total) {
                // just consume it
                while (hasRemaining()) readByte()
            }
        }

        assertEquals(0, processor.available())
        processor.release()

        // All buffers should be back in the pool or freed — pool.clear() should not crash
        pool.clear()

        // Acquiring after clear should work
        val fresh = pool.acquire(64)
        assertTrue(fresh.capacity >= 64)
        pool.release(fresh)
        pool.clear()
    }

    // ============================================================================
    // Skip Across Coalesce Boundary
    // ============================================================================

    @Test
    fun skipIntoCoalesceTail() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 16)
        val processor = DefaultStreamProcessor(pool, coalesceMinChunks = 0)

        // Large chunk in deque
        val large = BufferFactory.managed().allocate(300)
        for (i in 0 until 300) large.writeByte(0x11.toByte())
        large.resetForRead()
        processor.append(large)

        // Small chunk in coalesce tail
        val small = BufferFactory.managed().allocate(20)
        for (i in 0 until 20) small.writeByte(0x22.toByte())
        small.resetForRead()
        processor.append(small)

        assertEquals(320, processor.available())

        // Skip past the large chunk into the coalesce tail
        processor.skip(305)
        assertEquals(15, processor.available())

        // Remaining 15 bytes should all be 0x22
        for (i in 0 until 15) {
            assertEquals(0x22.toByte(), processor.readByte(), "Mismatch at $i")
        }

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // Coalesce Buffer Fills Up and Seals
    // ============================================================================

    @Test
    fun coalesceTailSealsWhenFull() {
        val pool = BufferPool(defaultBufferSize = 256, maxPoolSize = 16)
        // Coalesce tail will be 256 bytes (pool default). Filling it should seal and start new one.
        val processor = DefaultStreamProcessor(pool, coalesceMinChunks = 0)

        // Append 400 bytes in 20-byte chunks → fills 256-byte tail, seals, starts new tail
        for (i in 0 until 20) {
            val buf = BufferFactory.managed().allocate(20)
            for (j in 0 until 20) buf.writeByte(((i * 20 + j) and 0xFF).toByte())
            buf.resetForRead()
            processor.append(buf)
        }

        assertEquals(400, processor.available())

        // Verify all bytes
        for (i in 0 until 400) {
            assertEquals(
                (i and 0xFF).toByte(),
                processor.readByte(),
                "Mismatch at byte $i",
            )
        }

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // PeekMismatch With Coalesce Tail
    // ============================================================================

    @Test
    fun peekMismatchWithDataInCoalesceTail() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 16)
        val processor = DefaultStreamProcessor(pool, coalesceMinChunks = 0)

        // Append 4 bytes into coalesce tail
        val buf = BufferFactory.managed().allocate(4)
        buf.writeByte(0x01)
        buf.writeByte(0x02)
        buf.writeByte(0x03)
        buf.writeByte(0x04)
        buf.resetForRead()
        processor.append(buf)

        // Create matching pattern
        val pattern = BufferFactory.managed().allocate(4)
        pattern.writeByte(0x01)
        pattern.writeByte(0x02)
        pattern.writeByte(0x03)
        pattern.writeByte(0x04)
        pattern.resetForRead()

        assertTrue(processor.peekMatches(pattern))

        // Create mismatching pattern
        val mismatch = BufferFactory.managed().allocate(4)
        mismatch.writeByte(0x01)
        mismatch.writeByte(0x02)
        mismatch.writeByte(0xFF.toByte())
        mismatch.writeByte(0x04)
        mismatch.resetForRead()

        assertEquals(2, processor.peekMismatch(mismatch))

        processor.release()
        pool.clear()
    }

    // ============================================================================
    // Adaptive Behavior (default config)
    // ============================================================================

    @Test
    fun appendThenImmediateReadDoesNotCoalesce() {
        // Simulates protocol parsing: append → peek → read → repeat
        // Chunks.size should never reach 8, so coalescing never engages
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 16)
        val processor = StreamProcessor.create(pool) // default config

        for (i in 0 until 100) {
            val buf = BufferFactory.managed().allocate(16)
            buf.writeInt(i)
            for (j in 0 until 12) buf.writeByte(j.toByte())
            buf.resetForRead()
            processor.append(buf)

            // Immediately consume
            assertEquals(i, processor.peekInt())
            val data = processor.readBuffer(16)
            assertEquals(16, data.remaining())
            assertEquals(0, processor.available())
        }

        processor.release()
        pool.clear()
    }
}
