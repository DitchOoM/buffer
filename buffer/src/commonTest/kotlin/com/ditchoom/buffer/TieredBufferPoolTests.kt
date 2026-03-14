package com.ditchoom.buffer

import com.ditchoom.buffer.pool.DEFAULT_NETWORK_BUFFER_SIZE
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.pool.TieredBufferPool
import com.ditchoom.buffer.pool.withBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TieredBufferPoolTests {
    // ============================================================================
    // Tier Routing Tests
    // ============================================================================

    @Test
    fun smallPacketUsesSmallPool() {
        val pool = TieredBufferPool(smallThreshold = 512, smallDefaultSize = 512)
        pool.withBuffer(4) { buffer ->
            assertTrue(buffer.capacity >= 4, "Buffer must be at least minSize")
            assertTrue(buffer.capacity <= 512, "Small request should get a small buffer, got ${buffer.capacity}")
        }
        assertEquals(1L, pool.smallPoolStats().totalAllocations)
        assertEquals(0L, pool.largePoolStats().totalAllocations)
        pool.clear()
    }

    @Test
    fun largePacketUsesLargePool() {
        val pool = TieredBufferPool(smallThreshold = 512, largeDefaultSize = DEFAULT_NETWORK_BUFFER_SIZE)
        pool.withBuffer(4096) { buffer ->
            assertTrue(buffer.capacity >= 4096, "Buffer must be at least minSize")
        }
        assertEquals(0L, pool.smallPoolStats().totalAllocations)
        assertEquals(1L, pool.largePoolStats().totalAllocations)
        pool.clear()
    }

    @Test
    fun exactThresholdUsesSmallPool() {
        val pool = TieredBufferPool(smallThreshold = 512, smallDefaultSize = 512)
        pool.withBuffer(512) { buffer ->
            assertTrue(buffer.capacity >= 512)
        }
        assertEquals(1L, pool.smallPoolStats().totalAllocations)
        assertEquals(0L, pool.largePoolStats().totalAllocations)
        pool.clear()
    }

    @Test
    fun aboveThresholdUsesLargePool() {
        val pool = TieredBufferPool(smallThreshold = 512, largeDefaultSize = DEFAULT_NETWORK_BUFFER_SIZE)
        pool.withBuffer(513) { buffer ->
            assertTrue(buffer.capacity >= 513)
        }
        assertEquals(0L, pool.smallPoolStats().totalAllocations)
        assertEquals(1L, pool.largePoolStats().totalAllocations)
        pool.clear()
    }

    @Test
    fun zeroSizeUsesSmallPool() {
        val pool = TieredBufferPool(smallThreshold = 512, smallDefaultSize = 512)
        pool.withBuffer(0) { buffer ->
            assertTrue(buffer.capacity >= 512, "Zero-size request should get smallDefaultSize")
        }
        assertEquals(1L, pool.smallPoolStats().totalAllocations)
        pool.clear()
    }

    // ============================================================================
    // Reuse Tests
    // ============================================================================

    @Test
    fun smallBufferReusedFromSmallPool() {
        val pool = TieredBufferPool(smallThreshold = 512, smallDefaultSize = 512)
        // Acquire and release a small buffer
        pool.withBuffer(4) { buffer ->
            buffer.writeInt(42)
        }
        // Second acquire should reuse
        pool.withBuffer(4) { buffer ->
            // Should be a pool hit
        }
        assertEquals(2L, pool.smallPoolStats().totalAllocations)
        assertEquals(1L, pool.smallPoolStats().poolHits)
        pool.clear()
    }

    @Test
    fun largeBufferReusedFromLargePool() {
        val pool = TieredBufferPool(smallThreshold = 512, largeDefaultSize = DEFAULT_NETWORK_BUFFER_SIZE)
        pool.withBuffer(4096) { buffer ->
            buffer.writeInt(42)
        }
        pool.withBuffer(4096) { buffer ->
            // Should be a pool hit
        }
        assertEquals(2L, pool.largePoolStats().totalAllocations)
        assertEquals(1L, pool.largePoolStats().poolHits)
        pool.clear()
    }

    @Test
    fun releaseRoutesToCorrectPool() {
        val pool = TieredBufferPool(smallThreshold = 512, smallDefaultSize = 512, largeDefaultSize = DEFAULT_NETWORK_BUFFER_SIZE)

        // Acquire and release small + large buffers
        val smallBuf = pool.acquire(4)
        val largeBuf = pool.acquire(4096)
        pool.release(smallBuf)
        pool.release(largeBuf)

        // Verify each pool received its buffer back
        assertEquals(1, pool.smallPoolStats().currentPoolSize)
        assertEquals(1, pool.largePoolStats().currentPoolSize)
        pool.clear()
    }

    // ============================================================================
    // Aggregated Stats Tests
    // ============================================================================

    @Test
    fun statsAggregatesBothPools() {
        val pool = TieredBufferPool(smallThreshold = 512, smallDefaultSize = 512, largeDefaultSize = DEFAULT_NETWORK_BUFFER_SIZE)

        pool.withBuffer(4) { } // small
        pool.withBuffer(4096) { } // large
        pool.withBuffer(4) { } // small (hit)
        pool.withBuffer(4096) { } // large (hit)

        val stats = pool.stats()
        assertEquals(4L, stats.totalAllocations)
        assertEquals(2L, stats.poolHits)
        assertEquals(2L, stats.poolMisses)
        pool.clear()
    }

    @Test
    fun hitRateTrackedSeparately() {
        val pool = TieredBufferPool(smallThreshold = 512, smallDefaultSize = 512, largeDefaultSize = DEFAULT_NETWORK_BUFFER_SIZE)

        // 3 small acquires: 1 miss + 2 hits
        pool.withBuffer(4) { }
        pool.withBuffer(4) { }
        pool.withBuffer(4) { }

        // 1 large acquire: 1 miss
        pool.withBuffer(4096) { }

        val small = pool.smallPoolStats()
        assertEquals(3L, small.totalAllocations)
        assertEquals(2L, small.poolHits)
        assertEquals(1L, small.poolMisses)

        val large = pool.largePoolStats()
        assertEquals(1L, large.totalAllocations)
        assertEquals(0L, large.poolHits)
        assertEquals(1L, large.poolMisses)

        pool.clear()
    }

    // ============================================================================
    // Clear Tests
    // ============================================================================

    @Test
    fun clearDrainsBothPools() {
        val pool = TieredBufferPool(smallThreshold = 512, smallDefaultSize = 512, largeDefaultSize = DEFAULT_NETWORK_BUFFER_SIZE)

        pool.withBuffer(4) { }
        pool.withBuffer(4096) { }

        // Both pools should have 1 buffer after withBuffer releases
        assertTrue(pool.smallPoolStats().currentPoolSize > 0 || pool.largePoolStats().currentPoolSize > 0)

        pool.clear()

        assertEquals(0, pool.smallPoolStats().currentPoolSize)
        assertEquals(0, pool.largePoolStats().currentPoolSize)
        assertEquals(0, pool.stats().currentPoolSize)
    }

    // ============================================================================
    // Threading Mode Tests
    // ============================================================================

    @Test
    fun singleThreadedMode() {
        val pool = TieredBufferPool(threadingMode = ThreadingMode.SingleThreaded)
        pool.withBuffer(4) { buffer ->
            buffer.writeInt(42)
            buffer.resetForRead()
            assertEquals(42, buffer.readInt())
        }
        pool.clear()
    }

    @Test
    fun multiThreadedMode() {
        val pool = TieredBufferPool(threadingMode = ThreadingMode.MultiThreaded)
        pool.withBuffer(4) { buffer ->
            buffer.writeInt(42)
            buffer.resetForRead()
            assertEquals(42, buffer.readInt())
        }
        pool.clear()
    }

    // ============================================================================
    // Data Integrity Tests
    // ============================================================================

    @Test
    fun smallBufferReadWriteIntegrity() {
        val pool = TieredBufferPool(smallThreshold = 512, smallDefaultSize = 512)
        pool.withBuffer(16) { buffer ->
            buffer.writeInt(0x12345678)
            buffer.writeShort(0x1234.toShort())
            buffer.writeByte(0x42)
            buffer.resetForRead()
            assertEquals(0x12345678, buffer.readInt())
            assertEquals(0x1234.toShort(), buffer.readShort())
            assertEquals(0x42.toByte(), buffer.readByte())
        }
        pool.clear()
    }

    @Test
    fun largeBufferReadWriteIntegrity() {
        val pool = TieredBufferPool(smallThreshold = 512, largeDefaultSize = DEFAULT_NETWORK_BUFFER_SIZE)
        pool.withBuffer(4096) { buffer ->
            val data = ByteArray(1024) { it.toByte() }
            buffer.writeBytes(data)
            buffer.resetForRead()
            val readBack = buffer.readByteArray(1024)
            assertTrue(data.contentEquals(readBack))
        }
        pool.clear()
    }

    // ============================================================================
    // MQTT-Realistic Scenarios
    // ============================================================================

    @Test
    fun mqttControlPacketPattern() {
        // Simulates MQTT control packet traffic: many tiny packets
        val pool = TieredBufferPool(smallThreshold = 512, smallDefaultSize = 512)

        // 10 PUBACK-sized acquires (4 bytes each)
        repeat(10) {
            pool.withBuffer(4) { buffer ->
                buffer.writeInt(0x40020000 or (it and 0xFFFF))
            }
        }

        val stats = pool.smallPoolStats()
        assertEquals(10L, stats.totalAllocations)
        // First acquire is a miss, subsequent 9 are hits
        assertEquals(9L, stats.poolHits)
        assertEquals(1L, stats.poolMisses)
        pool.clear()
    }

    @Test
    fun mqttMixedTrafficPattern() {
        // Simulates mixed MQTT traffic: control packets + data publishes
        val pool =
            TieredBufferPool(
                smallThreshold = 512,
                smallDefaultSize = 512,
                largeDefaultSize = DEFAULT_NETWORK_BUFFER_SIZE,
            )

        // Interleaved small (PUBACK) and large (PUBLISH) acquires
        repeat(5) {
            pool.withBuffer(4) { buffer ->
                buffer.writeInt(0x40020000 or (it and 0xFFFF))
            }
            pool.withBuffer(4096) { buffer ->
                buffer.writeBytes(ByteArray(1024))
            }
        }

        // Each pool should have high hit rate independently
        val small = pool.smallPoolStats()
        assertEquals(5L, small.totalAllocations)
        assertEquals(4L, small.poolHits) // first miss, then 4 hits

        val large = pool.largePoolStats()
        assertEquals(5L, large.totalAllocations)
        assertEquals(4L, large.poolHits) // first miss, then 4 hits

        pool.clear()
    }

    @Test
    fun oversizedRequestGetsExactAllocation() {
        val pool =
            TieredBufferPool(
                smallThreshold = 512,
                smallDefaultSize = 512,
                largeDefaultSize = DEFAULT_NETWORK_BUFFER_SIZE,
            )

        // Request larger than largeDefaultSize — should still work
        pool.withBuffer(32768) { buffer ->
            assertTrue(buffer.capacity >= 32768, "Oversized request must be honored")
        }

        assertEquals(1L, pool.largePoolStats().totalAllocations)
        pool.clear()
    }

    // ============================================================================
    // Custom Factory Tests
    // ============================================================================

    @Test
    fun customFactoryUsedByBothPools() {
        var allocations = 0
        val countingFactory =
            object : BufferFactory {
                override fun allocate(
                    size: Int,
                    byteOrder: ByteOrder,
                ): PlatformBuffer {
                    allocations++
                    return BufferFactory.Default.allocate(size, byteOrder)
                }

                override fun wrap(
                    array: ByteArray,
                    byteOrder: ByteOrder,
                ): PlatformBuffer = BufferFactory.Default.wrap(array, byteOrder)
            }

        val pool = TieredBufferPool(factory = countingFactory)
        pool.withBuffer(4) { } // small → factory
        pool.withBuffer(4096) { } // large → factory
        assertEquals(2, allocations, "Both pools should use the provided factory")
        pool.clear()
    }
}
