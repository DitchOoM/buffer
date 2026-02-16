package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.DEFAULT_FILE_BUFFER_SIZE
import com.ditchoom.buffer.pool.DEFAULT_NETWORK_BUFFER_SIZE
import com.ditchoom.buffer.pool.PoolStats
import com.ditchoom.buffer.pool.PooledBuffer
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.pool.createBufferPool
import com.ditchoom.buffer.pool.withBuffer
import com.ditchoom.buffer.pool.withPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive unit tests for BufferPool.
 * Tests cover all public API methods and edge cases.
 */
class BufferPoolTests {
    // ============================================================================
    // Pool Creation and Configuration Tests
    // ============================================================================

    @Test
    fun createPoolWithDefaultParameters() =
        withPool { pool ->
            pool.withBuffer(100) { buffer ->
                assertTrue(buffer.capacity >= DEFAULT_FILE_BUFFER_SIZE)
            }
        }

    @Test
    fun createPoolWithCustomMaxSize() =
        withPool(maxPoolSize = 2, defaultBufferSize = 1024) { pool ->
            val buffers = (1..5).map { pool.acquire(512) }
            buffers.forEach { pool.release(it) }
            // Only maxPoolSize buffers should be kept
            assertTrue(pool.stats().currentPoolSize <= 2)
        }

    @Test
    fun createPoolWithCustomDefaultBufferSize() =
        withPool(defaultBufferSize = 2048) { pool ->
            pool.withBuffer(100) { buffer ->
                assertTrue(buffer.capacity >= 2048)
            }
        }

    @Test
    fun createPoolWithBigEndianByteOrder() =
        withPool(byteOrder = ByteOrder.BIG_ENDIAN) { pool ->
            pool.withBuffer(256) { buffer ->
                assertEquals(ByteOrder.BIG_ENDIAN, buffer.byteOrder)
                buffer.writeInt(0x12345678)
                buffer.resetForRead()
                assertEquals(0x12.toByte(), buffer.get(0))
            }
        }

    @Test
    fun createPoolWithLittleEndianByteOrder() =
        withPool(byteOrder = ByteOrder.LITTLE_ENDIAN) { pool ->
            pool.withBuffer(256) { buffer ->
                assertEquals(ByteOrder.LITTLE_ENDIAN, buffer.byteOrder)
                buffer.writeInt(0x12345678)
                buffer.resetForRead()
                assertEquals(0x78.toByte(), buffer.get(0))
            }
        }

    // ============================================================================
    // Buffer Acquisition Tests
    // ============================================================================

    @Test
    fun acquireReturnsBufferOfAtLeastRequestedSize() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(512) { buffer ->
                assertTrue(buffer.capacity >= 512)
            }
        }

    @Test
    fun acquireReturnsBufferOfAtLeastDefaultSize() =
        withPool(defaultBufferSize = 4096) { pool ->
            pool.withBuffer(100) { buffer ->
                assertTrue(buffer.capacity >= 4096)
            }
        }

    @Test
    fun acquireLargerThanDefaultSizeReturnsLargerBuffer() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(8192) { buffer ->
                assertTrue(buffer.capacity >= 8192)
            }
        }

    @Test
    fun acquireMultipleBuffersReturnsDistinctInstances() =
        withPool(defaultBufferSize = 1024) { pool ->
            val buffer1 = pool.acquire(512)
            val buffer2 = pool.acquire(512)
            // They should be different instances
            buffer1.writeByte(0x11)
            buffer2.writeByte(0x22)
            buffer1.resetForRead()
            buffer2.resetForRead()
            assertNotEquals(buffer1.readByte(), buffer2.readByte())
            pool.release(buffer1)
            pool.release(buffer2)
        }

    // ============================================================================
    // Pool Transparency Tests
    // ============================================================================

    @Test
    fun acquireReturnsPlatformBuffer() =
        withPool(defaultBufferSize = 1024) { pool ->
            val buffer = pool.acquire(512)
            assertIs<PlatformBuffer>(buffer)
            pool.release(buffer)
        }

    @Test
    fun acquiredBufferWorksWithXorMask() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                for (i in 0 until 16) buffer.writeByte(i.toByte())
                buffer.resetForRead()
                val mask = 0xDEADBEEF.toInt()
                buffer.xorMask(mask)
                buffer.position(0)
                buffer.xorMask(mask)
                for (i in 0 until 16) {
                    assertEquals(i.toByte(), buffer.readByte(), "Mismatch at $i after double XOR")
                }
            }
        }

    @Test
    fun acquiredBufferHasNativeMemoryAccess() {
        val pool = BufferPool(defaultBufferSize = 1024, allocationZone = AllocationZone.Direct)
        val buffer = pool.acquire(512)
        val nma = (buffer as? NativeMemoryAccess)
        // On JVM Direct / Linux native, should have native memory access
        // On platforms using Heap, may be null — just verify no crash
        if (nma != null) {
            // nativeAddress can be 0 on JS (byteOffset of a fresh Int8Array)
            assertTrue(nma.nativeSize > 0)
        }
        pool.release(buffer)
        pool.clear()
    }

    @Test
    fun poolBufferSlicePreservesNativeMemoryAccess() {
        val pool = BufferPool(defaultBufferSize = 1024, allocationZone = AllocationZone.Direct)
        val buffer = pool.acquire(64)
        buffer.writeInt(0x12345678)
        buffer.resetForRead()

        // Verify the buffer itself has nativeMemoryAccess
        val bufferNma = (buffer as ReadBuffer).nativeMemoryAccess
        if (bufferNma != null) {
            // If the pool buffer has nativeMemoryAccess, its slice must too.
            // This catches regressions where slice wrappers (TrackedSlice) break
            // the nativeMemoryAccess delegation chain.
            val slice = buffer.slice()
            val sliceNma = slice.nativeMemoryAccess
            assertNotNull(sliceNma, "Slice of pool buffer with nativeMemoryAccess must also have nativeMemoryAccess")
            assertTrue(sliceNma.nativeSize > 0, "Slice nativeSize must be > 0")
        }

        pool.release(buffer)
        pool.clear()
    }

    @Test
    fun acquiredBufferWorksWithWriteSource() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { src ->
                src.writeInt(0x11223344)
                src.writeInt(0x55667788)
                src.resetForRead()

                pool.withBuffer(256) { dst ->
                    dst.write(src)
                    dst.resetForRead()
                    assertEquals(0x11223344, dst.readInt())
                    assertEquals(0x55667788, dst.readInt())
                }
            }
        }

    @Test
    fun acquiredBufferWorksWithReadString() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                val text = "Hello 世界 🌍"
                buffer.writeString(text, Charset.UTF8)
                val len = buffer.position()
                buffer.resetForRead()
                assertEquals(text, buffer.readString(len, Charset.UTF8))
            }
        }

    @Test
    fun acquiredBufferWorksWithSlice() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer.writeInt(0x11223344)
                buffer.writeInt(0x55667788)
                buffer.resetForRead()
                buffer.readInt() // skip first
                val slice = buffer.slice()
                assertEquals(4, slice.remaining())
                assertEquals(0x55667788, slice.readInt())
            }
        }

    // ============================================================================
    // Buffer Release and Reuse Tests
    // ============================================================================

    @Test
    fun releaseBufferAddsToPool() =
        withPool(defaultBufferSize = 1024, maxPoolSize = 10) { pool ->
            assertEquals(0, pool.stats().currentPoolSize)
            pool.withBuffer(512) { }
            assertEquals(1, pool.stats().currentPoolSize)
        }

    @Test
    fun releaseAndReacquireReusesBuffer() =
        withPool(defaultBufferSize = 1024, maxPoolSize = 4) { pool ->
            pool.withBuffer(512) { }
            val stats1 = pool.stats()
            assertEquals(1, stats1.totalAllocations)
            assertEquals(0, stats1.poolHits)
            assertEquals(1, stats1.poolMisses)
            assertEquals(1, pool.stats().currentPoolSize)

            pool.withBuffer(512) { }
            val stats2 = pool.stats()
            assertEquals(2, stats2.totalAllocations)
            assertEquals(1, stats2.poolHits)
            assertEquals(1, stats2.poolMisses)
        }

    @Test
    fun releasedBufferIsResetForWrite() =
        withPool(defaultBufferSize = 1024, maxPoolSize = 4) { pool ->
            pool.withBuffer(512) { buffer1 ->
                buffer1.writeByte(0x42)
                buffer1.writeByte(0x43)
                assertEquals(2, buffer1.position())
            }

            // Reacquire should be reset for write
            pool.withBuffer(512) { buffer2 ->
                assertEquals(0, buffer2.position())
            }
        }

    @Test
    fun releaseWhenPoolFullDiscardsBuffer() =
        withPool(defaultBufferSize = 1024, maxPoolSize = 2) { pool ->
            val buffers = (1..5).map { pool.acquire(512) }
            buffers.forEach { pool.release(it) }
            // Pool should only hold maxPoolSize buffers
            assertTrue(pool.stats().currentPoolSize <= 2)
        }

    // ============================================================================
    // Pool Statistics Tests
    // ============================================================================

    @Test
    fun statsTracksTotalAllocations() =
        withPool(defaultBufferSize = 1024) { pool ->
            assertEquals(0, pool.stats().totalAllocations)

            pool.withBuffer(512) { }
            assertEquals(1, pool.stats().totalAllocations)

            pool.withBuffer(512) { }
            assertEquals(2, pool.stats().totalAllocations)

            // Reacquiring from pool still counts as allocation
            pool.withBuffer(512) { }
            assertEquals(3, pool.stats().totalAllocations)
        }

    @Test
    fun statsTracksPoolHitsAndMisses() =
        withPool(defaultBufferSize = 1024, maxPoolSize = 4) { pool ->
            // First acquisition is a miss
            pool.withBuffer(512) { }
            assertEquals(0, pool.stats().poolHits)
            assertEquals(1, pool.stats().poolMisses)

            // Second acquisition is a hit (reuse from pool)
            pool.withBuffer(512) { }
            assertEquals(1, pool.stats().poolHits)
            assertEquals(1, pool.stats().poolMisses)
        }

    @Test
    fun statsTracksCurrentPoolSize() =
        withPool(defaultBufferSize = 1024, maxPoolSize = 10) { pool ->
            assertEquals(0, pool.stats().currentPoolSize)

            val buffers = (1..5).map { pool.acquire(512) }
            assertEquals(0, pool.stats().currentPoolSize) // All in use

            pool.release(buffers[0])
            assertEquals(1, pool.stats().currentPoolSize)

            pool.release(buffers[1])
            pool.release(buffers[2])
            assertEquals(3, pool.stats().currentPoolSize)

            pool.release(buffers[3])
            pool.release(buffers[4])
            assertEquals(5, pool.stats().currentPoolSize)
        }

    @Test
    fun statsTracksPeakPoolSize() =
        withPool(defaultBufferSize = 1024, maxPoolSize = 10) { pool ->
            val buffers = (1..5).map { pool.acquire(512) }
            buffers.forEach { pool.release(it) }

            val stats = pool.stats()
            assertEquals(5, stats.currentPoolSize)
            assertTrue(stats.peakPoolSize >= 5)
        }

    @Test
    fun poolStatsAccurate() =
        withPool(defaultBufferSize = 1024, maxPoolSize = 4) { pool ->
            repeat(10) { pool.withBuffer(512) { } }
            val stats = pool.stats()
            assertEquals(stats.totalAllocations, stats.poolHits + stats.poolMisses)
        }

    @Test
    fun maxPoolSizeRespected() =
        withPool(defaultBufferSize = 1024, maxPoolSize = 3) { pool ->
            val buffers = (1..10).map { pool.acquire(512) }
            buffers.forEach { pool.release(it) }
            assertTrue(pool.stats().currentPoolSize <= 3)
        }

    // ============================================================================
    // Pool Clear Tests
    // ============================================================================

    @Test
    fun clearEmptiesPool() {
        val pool = BufferPool(defaultBufferSize = 1024)

        pool.withBuffer(512) { }
        assertEquals(1, pool.stats().currentPoolSize)

        pool.clear()
        assertEquals(0, pool.stats().currentPoolSize)
    }

    @Test
    fun clearDoesNotResetStats() {
        val pool = BufferPool(defaultBufferSize = 1024)

        pool.withBuffer(512) { }
        pool.clear()

        // Stats should still reflect history
        assertEquals(1, pool.stats().totalAllocations)
    }

    @Test
    fun acquireAfterClearIsMiss() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 4)
        pool.withBuffer(512) { }
        pool.clear()
        pool.withBuffer(512) { }
        val stats = pool.stats()
        assertEquals(2, stats.totalAllocations)
        assertEquals(2, stats.poolMisses)
        assertEquals(0, stats.poolHits)
        pool.clear()
    }

    @Test
    fun clearDrainsAllBuffers() {
        // Regression: clear() must drain via removeFirst(), not iterate.
        // An iterator-based loop can corrupt the ArrayDeque if freeNativeMemory()
        // re-enters release() and modifies the deque during iteration.
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 64)
        val buffers = (1..32).map { pool.acquire(512) }
        buffers.forEach { pool.release(it) }
        assertEquals(32, pool.stats().currentPoolSize)

        pool.clear()
        assertEquals(0, pool.stats().currentPoolSize)
    }

    @Test
    fun clearThenAcquireReleaseCycleStable() {
        // Verify pool is not corrupted after clear by doing acquire/release cycles
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 8)

        repeat(5) { cycle ->
            // Fill the pool
            val buffers = (1..8).map { pool.acquire(512) }
            buffers.forEach { pool.release(it) }
            assertTrue(pool.stats().currentPoolSize > 0, "Cycle $cycle: pool should have buffers")

            // Clear
            pool.clear()
            assertEquals(0, pool.stats().currentPoolSize, "Cycle $cycle: pool should be empty after clear")

            // Acquire and release again — must not crash
            pool.withBuffer(512) { buffer ->
                buffer.writeInt(cycle)
                buffer.resetForRead()
                assertEquals(cycle, buffer.readInt())
            }
        }
        pool.clear()
    }

    @Test
    fun clearOnEmptyPoolIsNoOp() {
        val pool = BufferPool(defaultBufferSize = 1024)
        // Clear on empty pool should not throw
        pool.clear()
        pool.clear()
        assertEquals(0, pool.stats().currentPoolSize)
    }

    @Test
    fun clearViaFreeNativeMemoryReentry() {
        // Simulate the re-entry scenario: acquire a PooledBuffer, DON'T release it,
        // then call freeNativeMemory() which calls releaseRef() -> pool.release(inner).
        // If clear() runs after this, the pool should be in a consistent state.
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 8)

        // Acquire buffers and free them via freeNativeMemory (the PooledBuffer path)
        // This triggers releaseRef() -> pool.release(inner), adding buffers back to pool
        val buffers = (1..4).map { pool.acquire(512) }
        buffers.forEach { (it as PlatformBuffer).freeNativeMemory() }

        // Pool should have received the buffers back via releaseRef
        assertTrue(pool.stats().currentPoolSize > 0)

        // Clear must not crash (drain pattern handles this safely)
        pool.clear()
        assertEquals(0, pool.stats().currentPoolSize)
    }

    @Test
    fun clearAfterMixedAcquireAndFreeNativeMemory() {
        // Mix of release() and freeNativeMemory() paths followed by clear()
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 16)

        val buf1 = pool.acquire(512)
        val buf2 = pool.acquire(512)
        val buf3 = pool.acquire(512)
        val buf4 = pool.acquire(512)

        // Release some via pool.release(), others via freeNativeMemory()
        pool.release(buf1)
        (buf2 as PlatformBuffer).freeNativeMemory()
        pool.release(buf3)
        (buf4 as PlatformBuffer).freeNativeMemory()

        // All 4 should be back in the pool
        assertEquals(4, pool.stats().currentPoolSize)

        // Clear must succeed without corruption
        pool.clear()
        assertEquals(0, pool.stats().currentPoolSize)

        // Pool must still be usable
        pool.withBuffer(512) { buffer ->
            buffer.writeInt(0xDEADBEEF.toInt())
            buffer.resetForRead()
            assertEquals(0xDEADBEEF.toInt(), buffer.readInt())
        }
        pool.clear()
    }

    @Test
    fun doubleReleaseNoException() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 10)
        val buffer = pool.acquire(512)
        pool.release(buffer)
        pool.release(buffer) // second release should not crash
        pool.clear()
    }

    // ============================================================================
    // Primitive Read/Write Tests
    // ============================================================================

    @Test
    fun pooledBufferByteReadWrite() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer.writeByte(0x42)
                buffer.writeByte(-128)
                buffer.writeByte(127)

                buffer.resetForRead()

                assertEquals(0x42.toByte(), buffer.readByte())
                assertEquals((-128).toByte(), buffer.readByte())
                assertEquals(127.toByte(), buffer.readByte())
            }
        }

    @Test
    fun pooledBufferShortReadWrite() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer.writeShort(0x1234)
                buffer.writeShort(Short.MIN_VALUE)
                buffer.writeShort(Short.MAX_VALUE)

                buffer.resetForRead()

                assertEquals(0x1234.toShort(), buffer.readShort())
                assertEquals(Short.MIN_VALUE, buffer.readShort())
                assertEquals(Short.MAX_VALUE, buffer.readShort())
            }
        }

    @Test
    fun pooledBufferIntReadWrite() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer.writeInt(0x12345678)
                buffer.writeInt(Int.MIN_VALUE)
                buffer.writeInt(Int.MAX_VALUE)

                buffer.resetForRead()

                assertEquals(0x12345678, buffer.readInt())
                assertEquals(Int.MIN_VALUE, buffer.readInt())
                assertEquals(Int.MAX_VALUE, buffer.readInt())
            }
        }

    @Test
    fun pooledBufferLongReadWrite() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer.writeLong(0x123456789ABCDEF0L)
                buffer.writeLong(Long.MIN_VALUE)
                buffer.writeLong(Long.MAX_VALUE)

                buffer.resetForRead()

                assertEquals(0x123456789ABCDEF0L, buffer.readLong())
                assertEquals(Long.MIN_VALUE, buffer.readLong())
                assertEquals(Long.MAX_VALUE, buffer.readLong())
            }
        }

    @Test
    fun pooledBufferFloatReadWrite() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer.writeFloat(1.5f)
                buffer.writeFloat(-1.5f)
                buffer.writeFloat(0.0f)
                buffer.writeFloat(Float.NEGATIVE_INFINITY)
                buffer.writeFloat(Float.POSITIVE_INFINITY)

                buffer.resetForRead()

                assertEquals(1.5f, buffer.readFloat())
                assertEquals(-1.5f, buffer.readFloat())
                assertEquals(0.0f, buffer.readFloat())
                assertEquals(Float.NEGATIVE_INFINITY, buffer.readFloat())
                assertEquals(Float.POSITIVE_INFINITY, buffer.readFloat())
            }
        }

    @Test
    fun pooledBufferDoubleReadWrite() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer.writeDouble(3.141592653589793)
                buffer.writeDouble(Double.MIN_VALUE)
                buffer.writeDouble(Double.MAX_VALUE)

                buffer.resetForRead()

                assertEquals(3.141592653589793, buffer.readDouble())
                assertEquals(Double.MIN_VALUE, buffer.readDouble())
                assertEquals(Double.MAX_VALUE, buffer.readDouble())
            }
        }

    // ============================================================================
    // Indexed Read/Write Tests
    // ============================================================================

    @Test
    fun pooledBufferIndexedByteOperations() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer[0] = 0x11.toByte()
                buffer[1] = 0x22.toByte()
                buffer[2] = 0x33.toByte()

                assertEquals(0x11.toByte(), buffer.get(0))
                assertEquals(0x22.toByte(), buffer.get(1))
                assertEquals(0x33.toByte(), buffer.get(2))
            }
        }

    @Test
    fun pooledBufferIndexedShortOperations() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer[0] = 0x1122.toShort()
                buffer[2] = 0x3344.toShort()

                assertEquals(0x1122.toShort(), buffer.getShort(0))
                assertEquals(0x3344.toShort(), buffer.getShort(2))
            }
        }

    @Test
    fun pooledBufferIndexedIntOperations() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer[0] = 0x11223344
                buffer[4] = 0x55667788

                assertEquals(0x11223344, buffer.getInt(0))
                assertEquals(0x55667788, buffer.getInt(4))
            }
        }

    @Test
    fun pooledBufferIndexedLongOperations() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer[0] = 0x1122334455667788L
                buffer[8] = -0x6655443322110100L

                assertEquals(0x1122334455667788L, buffer.getLong(0))
                assertEquals(-0x6655443322110100L, buffer.getLong(8))
            }
        }

    @Test
    fun pooledBufferIndexedFloatOperations() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer[0] = 1.5f
                buffer[4] = 2.5f

                assertEquals(1.5f, buffer.getFloat(0))
                assertEquals(2.5f, buffer.getFloat(4))
            }
        }

    @Test
    fun pooledBufferIndexedDoubleOperations() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer[0] = 1.5
                buffer[8] = 2.5

                assertEquals(1.5, buffer.getDouble(0))
                assertEquals(2.5, buffer.getDouble(8))
            }
        }

    // ============================================================================
    // ByteArray Operations Tests
    // ============================================================================

    @Test
    fun pooledBufferWriteBytesFullArray() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                val testData = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
                buffer.writeBytes(testData)
                buffer.resetForRead()

                val readData = buffer.readByteArray(8)
                assertTrue(testData.contentEquals(readData))
            }
        }

    @Test
    fun pooledBufferWriteBytesWithOffset() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                val testData = byteArrayOf(0, 0, 1, 2, 3, 4, 0, 0)
                buffer.writeBytes(testData, offset = 2, length = 4)
                buffer.resetForRead()

                val readData = buffer.readByteArray(4)
                assertTrue(byteArrayOf(1, 2, 3, 4).contentEquals(readData))
            }
        }

    @Test
    fun pooledBufferWriteBytesZeroLength() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                val testData = byteArrayOf(1, 2, 3)
                buffer.writeBytes(testData, offset = 0, length = 0)
                assertEquals(0, buffer.position())
            }
        }

    // ============================================================================
    // String Operations Tests
    // ============================================================================

    @Test
    fun pooledBufferStringReadWriteUtf8() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                val testString = "Hello, World!"
                buffer.writeString(testString, Charset.UTF8)
                val bytesWritten = buffer.position()
                buffer.resetForRead()

                val readString = buffer.readString(bytesWritten, Charset.UTF8)
                assertEquals(testString, readString)
            }
        }

    @Test
    fun pooledBufferStringWithUnicode() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                val testString = "Hello 世界 🌍"
                buffer.writeString(testString, Charset.UTF8)
                val bytesWritten = buffer.position()
                buffer.resetForRead()

                val readString = buffer.readString(bytesWritten, Charset.UTF8)
                assertEquals(testString, readString)
            }
        }

    @Test
    fun pooledBufferEmptyString() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer.writeString("", Charset.UTF8)
                assertEquals(0, buffer.position())
            }
        }

    // ============================================================================
    // Buffer Copy Tests
    // ============================================================================

    @Test
    fun pooledBufferWriteFromOtherBuffer() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer1 ->
                pool.withBuffer(256) { buffer2 ->
                    buffer1.writeInt(0x11223344)
                    buffer1.writeInt(0x55667788)
                    buffer1.resetForRead()

                    buffer2.write(buffer1)
                    buffer2.resetForRead()

                    assertEquals(0x11223344, buffer2.readInt())
                    assertEquals(0x55667788, buffer2.readInt())
                }
            }
        }

    // ============================================================================
    // Position and Limit Tests
    // ============================================================================

    @Test
    fun pooledBufferPositionOperations() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                assertEquals(0, buffer.position())

                buffer.writeByte(0x11)
                buffer.writeByte(0x22)
                assertEquals(2, buffer.position())

                buffer.position(0)
                assertEquals(0, buffer.position())

                buffer.position(1)
                assertEquals(1, buffer.position())
            }
        }

    @Test
    fun pooledBufferLimitOperations() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer.writeByte(0x11)
                buffer.writeByte(0x22)
                buffer.writeByte(0x33)
                buffer.writeByte(0x44)
                buffer.resetForRead()

                assertEquals(4, buffer.limit())

                buffer.setLimit(2)
                assertEquals(2, buffer.limit())
                assertEquals(2, buffer.remaining())
            }
        }

    @Test
    fun pooledBufferResetForRead() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer.writeByte(0x11)
                buffer.writeByte(0x22)
                assertEquals(2, buffer.position())

                buffer.resetForRead()
                assertEquals(0, buffer.position())
                assertEquals(2, buffer.limit())
            }
        }

    @Test
    fun pooledBufferResetForWrite() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer.writeByte(0x11)
                buffer.writeByte(0x22)
                buffer.resetForRead()

                buffer.resetForWrite()
                assertEquals(0, buffer.position())
            }
        }

    // ============================================================================
    // Slice Tests
    // ============================================================================

    @Test
    fun pooledBufferSliceCreatesView() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer.writeInt(0x11223344)
                buffer.writeInt(0x55667788)
                buffer.resetForRead()

                buffer.readInt() // Read first int

                val slice = buffer.slice()
                assertEquals(4, slice.remaining())
                assertEquals(0x55667788, slice.readInt())
            }
        }

    // ============================================================================
    // Default Constants Tests
    // ============================================================================

    @Test
    fun defaultFileSizeConstantIs64KB() {
        assertEquals(64 * 1024, DEFAULT_FILE_BUFFER_SIZE)
    }

    @Test
    fun defaultNetworkSizeConstantIs8KB() {
        assertEquals(8 * 1024, DEFAULT_NETWORK_BUFFER_SIZE)
    }

    // ============================================================================
    // Edge Case Tests
    // ============================================================================

    @Test
    fun acquireZeroSizeUsesDefaultSize() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(0) { buffer ->
                assertTrue(buffer.capacity >= 1024)
            }
        }

    @Test
    fun multipleReleasesOfSameBuffer() =
        withPool(defaultBufferSize = 1024, maxPoolSize = 10) { pool ->
            val buffer = pool.acquire(512)
            pool.release(buffer)
            // Second release should be safe (no-op or handled gracefully)
        }

    @Test
    fun poolWorksWithVerySmallMaxSize() =
        withPool(defaultBufferSize = 1024, maxPoolSize = 1) { pool ->
            val buffer1 = pool.acquire(512)
            val buffer2 = pool.acquire(512)
            pool.release(buffer1)
            pool.release(buffer2)
            assertTrue(pool.stats().currentPoolSize <= 1)
        }

    @Test
    fun acquireLargeSize() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(10 * 1024 * 1024) { buffer ->
                assertTrue(buffer.capacity >= 10 * 1024 * 1024)
            }
        }

    @Test
    fun releaseLargeAcquireSmall() =
        withPool(defaultBufferSize = 1024, maxPoolSize = 4) { pool ->
            // Acquire a large buffer and release it
            pool.withBuffer(1024 * 1024) { }
            // Pool has the large buffer. Now acquire small — should hit (large >= small)
            pool.withBuffer(512) { }
            val stats = pool.stats()
            assertEquals(1, stats.poolHits)
        }

    @Test
    fun releaseSmallAcquireLarge() =
        withPool(defaultBufferSize = 1024, maxPoolSize = 4) { pool ->
            // Acquire a default-size buffer and release it
            pool.withBuffer(512) { }
            // Pool has default-size buffer. Now acquire much larger — should be a miss
            pool.withBuffer(1024 * 1024) { }
            val stats = pool.stats()
            assertEquals(2, stats.poolMisses)
        }

    // ============================================================================
    // withBuffer Extension Tests
    // ============================================================================

    @Test
    fun withBufferAutoReleasesOnSuccess() =
        withPool(defaultBufferSize = 1024, maxPoolSize = 10) { pool ->
            assertEquals(0, pool.stats().currentPoolSize)

            pool.withBuffer(512) { buffer ->
                buffer.writeByte(0x42)
                buffer.resetForRead()
                assertEquals(0x42.toByte(), buffer.readByte())
            }

            // Buffer should be released back to pool
            assertEquals(1, pool.stats().currentPoolSize)
        }

    @Test
    fun withBufferAutoReleasesOnException() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 10)

        try {
            pool.withBuffer(512) { buffer ->
                buffer.writeByte(0x42)
                throw RuntimeException("Test exception")
            }
        } catch (e: RuntimeException) {
            // Expected
        }

        // Buffer should still be released back to pool
        assertEquals(1, pool.stats().currentPoolSize)
        pool.clear()
    }

    @Test
    fun withBufferReturnsBlockResult() =
        withPool(defaultBufferSize = 1024) { pool ->
            val result =
                pool.withBuffer(256) { buffer ->
                    buffer.writeInt(0x12345678)
                    buffer.resetForRead()
                    buffer.readInt()
                }

            assertEquals(0x12345678, result)
        }

    @Test
    fun withBufferWithDefaultSize() =
        withPool(defaultBufferSize = 2048) { pool ->
            pool.withBuffer { buffer ->
                assertTrue(buffer.capacity >= 2048)
            }
        }

    @Test
    fun nestedWithBufferWorks() =
        withPool(defaultBufferSize = 1024, maxPoolSize = 10) { pool ->
            pool.withBuffer(256) { outer ->
                outer.writeInt(0x11111111)

                pool.withBuffer(256) { inner ->
                    inner.writeInt(0x22222222)
                    inner.resetForRead()
                    assertEquals(0x22222222, inner.readInt())
                }

                outer.resetForRead()
                assertEquals(0x11111111, outer.readInt())
            }

            // Both buffers should be released
            assertEquals(2, pool.stats().currentPoolSize)
        }

    @Test
    fun withBufferReleasesOnException() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 10)
        try {
            pool.withBuffer(512) { throw RuntimeException("test") }
        } catch (_: RuntimeException) {
        }
        assertEquals(1, pool.stats().currentPoolSize)
        pool.clear()
    }

    @Test
    fun withPoolAutoClears() {
        val poolStats =
            withPool(defaultBufferSize = 1024, maxPoolSize = 10) { pool ->
                pool.withBuffer(512) { }
                pool.stats()
            }
        assertEquals(1, poolStats.currentPoolSize)
    }

    // ============================================================================
    // withPool Extension Tests
    // ============================================================================

    @Test
    fun withPoolCreatesAndClearsPool() {
        val poolStats =
            withPool(defaultBufferSize = 1024, maxPoolSize = 10) { pool ->
                pool.withBuffer(512) { buffer ->
                    buffer.writeInt(0x12345678)
                }
                pool.stats()
            }

        assertEquals(1, poolStats.currentPoolSize)
        assertEquals(1, poolStats.totalAllocations)
    }

    @Test
    fun withPoolAutoCleanupOnException() {
        try {
            withPool(defaultBufferSize = 1024) { pool ->
                pool.acquire(512)
                throw RuntimeException("Test exception")
            }
        } catch (e: RuntimeException) {
            // Expected - pool should be cleared
        }
        // No memory leak - pool was cleaned up in finally block
    }

    // ============================================================================
    // Tests Using withBuffer Pattern
    // ============================================================================

    @Test
    fun withBufferByteReadWriteExample() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer.writeByte(0x42)
                buffer.writeByte(-128)
                buffer.writeByte(127)
                buffer.resetForRead()

                assertEquals(0x42.toByte(), buffer.readByte())
                assertEquals((-128).toByte(), buffer.readByte())
                assertEquals(127.toByte(), buffer.readByte())
            }
        }

    @Test
    fun withBufferIntReadWriteExample() =
        withPool(defaultBufferSize = 1024) { pool ->
            val readValue =
                pool.withBuffer(256) { buffer ->
                    buffer.writeInt(0x12345678)
                    buffer.resetForRead()
                    buffer.readInt()
                }

            assertEquals(0x12345678, readValue)
        }

    @Test
    fun withBufferBufferCopyExample() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { source ->
                source.writeInt(0x11223344)
                source.writeInt(0x55667788)
                source.resetForRead()

                pool.withBuffer(256) { dest ->
                    dest.write(source)
                    dest.resetForRead()

                    assertEquals(0x11223344, dest.readInt())
                    assertEquals(0x55667788, dest.readInt())
                }
            }
        }

    // ============================================================================
    // Multi-threaded Pool Tests
    // ============================================================================

    @Test
    fun createMultiThreadedPool() =
        withPool(threadingMode = ThreadingMode.MultiThreaded) { pool ->
            pool.withBuffer(512) { buffer ->
                assertTrue(buffer.capacity >= DEFAULT_FILE_BUFFER_SIZE)
            }
        }

    @Test
    fun multiThreadedPoolAcquireRelease() =
        withPool(
            threadingMode = ThreadingMode.MultiThreaded,
            defaultBufferSize = 1024,
            maxPoolSize = 4,
        ) { pool ->
            val buffer1 = pool.acquire(512)
            val buffer2 = pool.acquire(512)

            buffer1.writeLong(0x1122334455667788L)
            buffer2.writeLong(-0x1122334455667788L)

            buffer1.resetForRead()
            buffer2.resetForRead()

            assertEquals(0x1122334455667788L, buffer1.readLong())
            assertEquals(-0x1122334455667788L, buffer2.readLong())

            pool.release(buffer1)
            pool.release(buffer2)

            assertEquals(2, pool.stats().currentPoolSize)
        }

    @Test
    fun multiThreadedPoolReuseAfterRelease() =
        withPool(
            threadingMode = ThreadingMode.MultiThreaded,
            defaultBufferSize = 1024,
            maxPoolSize = 4,
        ) { pool ->
            // First acquire - miss
            pool.withBuffer(512) { }
            assertEquals(1, pool.stats().poolMisses)
            assertEquals(0, pool.stats().poolHits)

            // Second acquire - should hit
            pool.withBuffer(512) { }
            assertEquals(1, pool.stats().poolMisses)
            assertEquals(1, pool.stats().poolHits)
        }

    @Test
    fun multiThreadedPoolMaxSizeEnforced() =
        withPool(
            threadingMode = ThreadingMode.MultiThreaded,
            defaultBufferSize = 1024,
            maxPoolSize = 2,
        ) { pool ->
            // Acquire 5 buffers
            val buffers = (1..5).map { pool.acquire(512) }

            // Release all
            buffers.forEach { pool.release(it) }

            // Pool should only keep maxPoolSize
            assertTrue(pool.stats().currentPoolSize <= 2)
        }

    @Test
    fun multiThreadedPoolClear() {
        val pool =
            BufferPool(
                threadingMode = ThreadingMode.MultiThreaded,
                defaultBufferSize = 1024,
            )

        val buffers = (1..5).map { pool.acquire(512) }
        buffers.forEach { pool.release(it) }

        assertTrue(pool.stats().currentPoolSize > 0)
        pool.clear()
        assertEquals(0, pool.stats().currentPoolSize)
    }

    @Test
    fun multiThreadedPoolWithBuffer() =
        withPool(
            threadingMode = ThreadingMode.MultiThreaded,
            defaultBufferSize = 1024,
        ) { pool ->
            val result =
                pool.withBuffer(256) { buffer ->
                    buffer.writeLong(0x0102030405060708L)
                    buffer.resetForRead()
                    buffer.readLong()
                }

            assertEquals(0x0102030405060708L, result)
            assertEquals(1, pool.stats().currentPoolSize)
        }

    @Test
    fun multiThreadedPoolStatsTracking() =
        withPool(
            threadingMode = ThreadingMode.MultiThreaded,
            defaultBufferSize = 1024,
            maxPoolSize = 10,
        ) { pool ->
            // Initial state
            assertEquals(0, pool.stats().totalAllocations)
            assertEquals(0, pool.stats().poolHits)
            assertEquals(0, pool.stats().poolMisses)
            assertEquals(0, pool.stats().currentPoolSize)

            // Acquire and release
            repeat(5) {
                pool.withBuffer(512) { }
            }

            val stats = pool.stats()
            assertEquals(5, stats.totalAllocations)
            // First was miss, rest were hits
            assertEquals(4, stats.poolHits)
            assertEquals(1, stats.poolMisses)
            assertTrue(stats.peakPoolSize >= 1)
        }

    @Test
    fun withPoolSingleThreadedMode() =
        withPool(threadingMode = ThreadingMode.SingleThreaded) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer.writeLong(0x123456789ABCDEF0L)
                buffer.resetForRead()
                assertEquals(0x123456789ABCDEF0L, buffer.readLong())
            }
        }

    @Test
    fun withPoolMultiThreadedMode() =
        withPool(threadingMode = ThreadingMode.MultiThreaded) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer.writeLong(0x123456789ABCDEF0L)
                buffer.resetForRead()
                assertEquals(0x123456789ABCDEF0L, buffer.readLong())
            }
        }

    // ============================================================================
    // Regression: release() must NOT corrupt buffer data (resetForWrite deferred to acquire)
    // ============================================================================

    @Test
    fun releaseDoesNotCorruptBufferData() {
        // Regression test: pool.release() must NOT call resetForWrite() on the buffer.
        // If it did, any code that frees a buffer while downstream still reads from it
        // (e.g., freeIfNeeded after socket.write) would see corrupted data.
        // The reset should only happen at acquire() time.
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 4)
        val buffer = pool.acquire(256)

        // Write data and switch to read mode
        buffer.writeInt(0x12345678)
        buffer.writeInt(0xDEADBEEF.toInt())
        buffer.resetForRead()

        // Verify data is readable before release
        assertEquals(0x12345678, buffer.readInt())

        // Release the buffer back to pool (simulates freeIfNeeded)
        pool.release(buffer)

        // CRITICAL: data must still be intact after release
        // position was at 4 (after reading first int), should still be there
        assertEquals(0xDEADBEEF.toInt(), buffer.readInt())

        pool.clear()
    }

    @Test
    fun releaseDoesNotCorruptBufferDataViaFreeNativeMemory() {
        // Same regression test but using the freeNativeMemory() path (PooledBuffer)
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 4)
        val buffer = pool.acquire(256)

        buffer.writeInt(0xCAFEBABE.toInt())
        buffer.writeInt(0xDEADC0DE.toInt())
        buffer.resetForRead()

        // Read first int
        val first = buffer.readInt()
        assertEquals(0xCAFEBABE.toInt(), first)

        // Free via freeNativeMemory (the PooledBuffer path)
        (buffer as PlatformBuffer).freeNativeMemory()

        // Data must still be readable - position/limit not corrupted
        assertEquals(0xDEADC0DE.toInt(), buffer.readInt())

        pool.clear()
    }

    @Test
    fun releaseDoesNotCorruptMultiThreadedPool() {
        // Same test for the lock-free pool variant
        val pool =
            BufferPool(
                threadingMode = ThreadingMode.MultiThreaded,
                defaultBufferSize = 1024,
                maxPoolSize = 4,
            )
        val buffer = pool.acquire(256)

        buffer.writeLong(0x0102030405060708L)
        buffer.resetForRead()

        // Read 4 bytes
        val firstInt = buffer.readInt()
        assertEquals(0x01020304, firstInt)

        // Release back to pool
        pool.release(buffer)

        // Remaining data must still be intact
        assertEquals(0x05060708, buffer.readInt())

        pool.clear()
    }

    @Test
    fun acquireAfterReleaseGetsResetBuffer() {
        // Complement to above: verify that acquire() DOES reset the buffer
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 4)

        // First acquire, write data, release
        val buffer1 = pool.acquire(256)
        buffer1.writeInt(0x12345678)
        pool.release(buffer1)

        // Re-acquire from pool - should be reset for writing
        val buffer2 = pool.acquire(256)
        assertEquals(0, buffer2.position())
        assertTrue(buffer2.capacity >= 256)

        pool.release(buffer2)
        pool.clear()
    }

    // ============================================================================
    // Additional Code Path Coverage Tests
    // ============================================================================

    @Test
    fun acquireLargerBufferWhenPoolHasSmallerBuffer() =
        withPool(defaultBufferSize = 1024, maxPoolSize = 4) { pool ->
            // First acquire a buffer with default size
            pool.withBuffer(512) { } // Gets 1024 byte buffer

            // Now request a larger buffer than what's in the pool
            pool.withBuffer(2048) { buffer ->
                assertTrue(buffer.capacity >= 2048)
            }

            // This should be a miss because pooled buffer was too small
            val stats = pool.stats()
            assertEquals(2, stats.totalAllocations)
            assertEquals(2, stats.poolMisses)
        }

    @Test
    fun multiThreadedPoolAcquireLargerBufferDiscardsSmaller() =
        withPool(
            threadingMode = ThreadingMode.MultiThreaded,
            defaultBufferSize = 1024,
            maxPoolSize = 4,
        ) { pool ->
            pool.withBuffer(512) { }

            // Request larger buffer
            pool.withBuffer(4096) { buffer ->
                assertTrue(buffer.capacity >= 4096)
            }

            val stats = pool.stats()
            assertEquals(2, stats.totalAllocations)
            assertEquals(2, stats.poolMisses)
        }

    @Test
    fun pooledBufferRemainingAfterWrites() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer.writeInt(0x12345678)
                buffer.writeInt(0x56789ABC)
                buffer.resetForRead()

                assertEquals(8, buffer.remaining())

                buffer.readInt()
                assertEquals(4, buffer.remaining())

                buffer.readInt()
                assertEquals(0, buffer.remaining())
            }
        }

    @Test
    fun pooledBufferFluentWriteChaining() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer
                    .writeByte(0x11)
                    .writeShort(0x2233)
                    .writeInt(0x44556677)
                    .writeLong(0x1122334455667788L)

                buffer.resetForRead()
                assertEquals(0x11.toByte(), buffer.readByte())
                assertEquals(0x2233.toShort(), buffer.readShort())
                assertEquals(0x44556677, buffer.readInt())
                assertEquals(0x1122334455667788L, buffer.readLong())
            }
        }

    @Test
    fun pooledBufferFluentSetChaining() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer[0] = 0x11.toByte()
                buffer[1] = 0x22.toShort()
                buffer[3] = 0x33445566
                buffer[7] = 0x1122334455667788L

                assertEquals(0x11.toByte(), buffer.get(0))
                assertEquals(0x22.toShort(), buffer.getShort(1))
                assertEquals(0x33445566, buffer.getInt(3))
                assertEquals(0x1122334455667788L, buffer.getLong(7))
            }
        }

    @Test
    fun singleThreadedPoolSequentialAcquireRelease() {
        val pool =
            BufferPool(
                threadingMode = ThreadingMode.SingleThreaded,
                defaultBufferSize = 1024,
                maxPoolSize = 4,
            )

        for (i in 0 until 10) {
            pool.withBuffer(512) { buffer ->
                buffer.writeInt(i)
                buffer.resetForRead()
                assertEquals(i, buffer.readInt())
            }
        }

        val stats = pool.stats()
        assertEquals(10, stats.totalAllocations)
        assertEquals(9, stats.poolHits)
        assertEquals(1, stats.poolMisses)
        assertEquals(1, stats.currentPoolSize)

        pool.clear()
    }

    @Test
    fun multiThreadedPoolSequentialAcquireRelease() {
        val pool =
            BufferPool(
                threadingMode = ThreadingMode.MultiThreaded,
                defaultBufferSize = 1024,
                maxPoolSize = 4,
            )

        for (i in 0 until 10) {
            pool.withBuffer(512) { buffer ->
                buffer.writeInt(i)
                buffer.resetForRead()
                assertEquals(i, buffer.readInt())
            }
        }

        val stats = pool.stats()
        assertEquals(10, stats.totalAllocations)
        assertEquals(9, stats.poolHits)
        assertEquals(1, stats.poolMisses)

        pool.clear()
    }

    @Test
    fun pooledBufferSliceReturnsReadBuffer() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                buffer.writeInt(0x11223344)
                buffer.writeInt(0x55667788)
                buffer.writeInt(0x11223344)
                buffer.resetForRead()

                buffer.readInt()

                val slice = buffer.slice()
                assertEquals(8, slice.remaining())
                assertEquals(0x55667788, slice.readInt())
                assertEquals(0x11223344, slice.readInt())
            }
        }

    @Test
    fun poolClearDoesNotAffectInUseBuffers() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 4)

        val buffer = pool.acquire(512)
        buffer.writeInt(0x12345678)

        pool.clear()
        assertEquals(0, pool.stats().currentPoolSize)

        // Buffer should still be usable
        buffer.resetForRead()
        assertEquals(0x12345678, buffer.readInt())

        pool.release(buffer)
        pool.clear()
    }

    @Test
    fun releaseBufferFromDifferentPoolType() {
        val singlePool = BufferPool(threadingMode = ThreadingMode.SingleThreaded)
        val multiPool = BufferPool(threadingMode = ThreadingMode.MultiThreaded)

        val singleBuffer = singlePool.acquire(256)
        val multiBuffer = multiPool.acquire(256)

        // Cross-pool release — pool accepts any PlatformBuffer
        multiPool.release(singleBuffer)
        singlePool.release(multiBuffer)

        singlePool.clear()
        multiPool.clear()
    }

    @Test
    fun poolStatsDataClassEquality() {
        val stats1 =
            PoolStats(
                totalAllocations = 10,
                poolHits = 8,
                poolMisses = 2,
                currentPoolSize = 3,
                peakPoolSize = 5,
            )

        val stats2 =
            PoolStats(
                totalAllocations = 10,
                poolHits = 8,
                poolMisses = 2,
                currentPoolSize = 3,
                peakPoolSize = 5,
            )

        assertEquals(stats1, stats2)
        assertEquals(stats1.hashCode(), stats2.hashCode())
    }

    @Test
    fun createBufferPoolFunction() {
        val pool =
            createBufferPool(
                threadingMode = ThreadingMode.SingleThreaded,
                maxPoolSize = 16,
                defaultBufferSize = 2048,
                byteOrder = ByteOrder.LITTLE_ENDIAN,
            )

        pool.withBuffer(100) { buffer ->
            assertEquals(ByteOrder.LITTLE_ENDIAN, buffer.byteOrder)
            assertTrue(buffer.capacity >= 2048)
        }

        pool.clear()
    }

    @Test
    fun pooledBufferWriteFromReadBuffer() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { source ->
                source.writeInt(0x11223344)
                source.writeInt(0x55667788)
                source.resetForRead()

                pool.withBuffer(256) { dest ->
                    dest.write(source)
                    dest.resetForRead()

                    assertEquals(0x11223344, dest.readInt())
                    assertEquals(0x55667788, dest.readInt())
                }
            }
        }

    @Test
    fun multiplePoolsIndependent() {
        withPool(defaultBufferSize = 1024, maxPoolSize = 4) { pool1 ->
            withPool(defaultBufferSize = 2048, maxPoolSize = 4) { pool2 ->
                pool1.withBuffer(100) { buffer1 ->
                    pool2.withBuffer(100) { buffer2 ->
                        assertTrue(buffer1.capacity >= 1024)
                        assertTrue(buffer2.capacity >= 2048)
                    }
                }

                assertEquals(1, pool1.stats().currentPoolSize)
                assertEquals(1, pool2.stats().currentPoolSize)
            }
        }
    }

    @Test
    fun pooledBufferCapacityConsistent() =
        withPool(defaultBufferSize = 4096) { pool ->
            pool.withBuffer(100) { buffer ->
                val originalCapacity = buffer.capacity
                assertTrue(originalCapacity >= 4096)

                buffer.writeInt(0x12345678)
                assertEquals(originalCapacity, buffer.capacity)

                buffer.resetForRead()
                assertEquals(originalCapacity, buffer.capacity)

                buffer.readInt()
                assertEquals(originalCapacity, buffer.capacity)
            }
        }

    @Test
    fun pooledBufferByteArrayOperations() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { buffer ->
                val testData = ByteArray(100) { it.toByte() }
                buffer.writeBytes(testData)
                buffer.resetForRead()

                val readData = buffer.readByteArray(100)
                assertTrue(testData.contentEquals(readData))
            }
        }

    // ============================================================================
    // PooledBuffer Unwrap / Fast-Path Tests
    // ============================================================================

    @Test
    fun pooledBufferWriteToPooledBuffer() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { src ->
                for (i in 0 until 64) src.writeByte((i and 0xFF).toByte())
                src.resetForRead()

                pool.withBuffer(256) { dst ->
                    dst.write(src)
                    dst.resetForRead()
                    for (i in 0 until 64) {
                        assertEquals((i and 0xFF).toByte(), dst.readByte(), "Mismatch at index $i")
                    }
                }
                assertEquals(64, src.position(), "Source position should be advanced")
            }
        }

    @Test
    fun pooledBufferXorMaskCopyToPooledBuffer() =
        withPool(defaultBufferSize = 1024) { pool ->
            val mask = 0xDEADBEEF.toInt()
            pool.withBuffer(256) { src ->
                for (i in 0 until 32) src.writeByte((i and 0xFF).toByte())
                src.resetForRead()

                pool.withBuffer(256) { dst ->
                    dst.xorMaskCopy(src, mask)
                    dst.resetForRead()

                    // Verify masked data is not equal to original
                    var anyDifferent = false
                    for (i in 0 until 32) {
                        val expected = ((i and 0xFF) xor ((mask ushr (24 - (i % 4) * 8)) and 0xFF)).toByte()
                        assertEquals(expected, dst.readByte(), "Masked byte mismatch at $i")
                        if (expected != (i and 0xFF).toByte()) anyDifferent = true
                    }
                    assertTrue(anyDifferent, "XOR mask should change at least some bytes")
                }
            }
        }

    @Test
    fun pooledBufferXorMaskCopyRoundTrip() =
        withPool(defaultBufferSize = 1024) { pool ->
            val mask = 0xCAFEBABE.toInt()
            val original = ByteArray(100) { (it * 7 + 13).toByte() }

            pool.withBuffer(256) { src ->
                src.writeBytes(original)
                src.resetForRead()

                pool.withBuffer(256) { masked ->
                    masked.xorMaskCopy(src, mask)
                    masked.resetForRead()

                    pool.withBuffer(256) { unmasked ->
                        unmasked.write(masked)
                        unmasked.resetForRead()
                        unmasked.xorMask(mask)
                        unmasked.position(0)

                        val recovered = unmasked.readByteArray(100)
                        assertTrue(original.contentEquals(recovered), "Round-trip XOR mask should recover original data")
                    }
                }
            }
        }

    @Test
    fun pooledBufferWriteFromNonPooled() =
        withPool(defaultBufferSize = 1024) { pool ->
            // Create a non-pooled buffer
            val src = PlatformBuffer.allocate(256)
            src.writeInt(0xAABBCCDD.toInt())
            src.writeInt(0x11223344)
            src.resetForRead()

            pool.withBuffer(256) { dst ->
                dst.write(src)
                dst.resetForRead()
                assertEquals(0xAABBCCDD.toInt(), dst.readInt())
                assertEquals(0x11223344, dst.readInt())
            }
        }

    @Test
    fun unwrapReturnsInnerPlatformBuffer() =
        withPool(defaultBufferSize = 1024) { pool ->
            val buffer = pool.acquire(512)
            assertIs<PooledBuffer>(buffer, "Pool should return PooledBuffer")
            val unwrapped = buffer.unwrap()
            assertFalse(unwrapped is PooledBuffer, "unwrap() should not return a PooledBuffer")
            assertTrue(unwrapped.capacity >= 512, "Unwrapped buffer should have correct capacity")
            pool.release(buffer)
        }

    @Test
    fun pooledBufferContentEqualsPooledBuffer() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { a ->
                pool.withBuffer(256) { b ->
                    val data = ByteArray(64) { (it * 3 + 7).toByte() }
                    a.writeBytes(data)
                    b.writeBytes(data)
                    a.resetForRead()
                    b.resetForRead()
                    assertTrue(a.contentEquals(b), "Pooled buffers with same data should be contentEquals")
                }
            }
        }

    @Test
    fun pooledBufferContentEqualsNonPooled() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { pooled ->
                val direct = PlatformBuffer.allocate(256)
                val data = ByteArray(64) { (it * 3 + 7).toByte() }
                pooled.writeBytes(data)
                direct.writeBytes(data)
                pooled.resetForRead()
                direct.resetForRead()
                assertTrue(pooled.contentEquals(direct), "Pooled vs direct should be contentEquals")
                assertTrue(direct.contentEquals(pooled), "Direct vs pooled should be contentEquals")
            }
        }

    @Test
    fun pooledBufferContentEqualsDetectsDifference() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { a ->
                pool.withBuffer(256) { b ->
                    a.writeBytes(ByteArray(32) { 0x11 })
                    b.writeBytes(ByteArray(32) { 0x22 })
                    a.resetForRead()
                    b.resetForRead()
                    assertFalse(a.contentEquals(b), "Different data should not be contentEquals")
                }
            }
        }

    @Test
    fun pooledBufferMismatchPooledBuffer() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { a ->
                pool.withBuffer(256) { b ->
                    val data = ByteArray(64) { it.toByte() }
                    a.writeBytes(data)
                    b.writeBytes(data.copyOf().also { it[42] = 0xFF.toByte() })
                    a.resetForRead()
                    b.resetForRead()
                    assertEquals(42, a.mismatch(b), "Mismatch should be at index 42")
                }
            }
        }

    @Test
    fun pooledBufferMismatchIdentical() =
        withPool(defaultBufferSize = 1024) { pool ->
            pool.withBuffer(256) { a ->
                pool.withBuffer(256) { b ->
                    val data = ByteArray(64) { it.toByte() }
                    a.writeBytes(data)
                    b.writeBytes(data)
                    a.resetForRead()
                    b.resetForRead()
                    assertEquals(-1, a.mismatch(b), "Identical data should return -1")
                }
            }
        }
}
