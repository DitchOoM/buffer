package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferSizeClass
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.pool.withPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A buffer released to a pool is found again by the next acquire of the same requested size. */
class BufferPoolSizeClassTests {
    private companion object {
        const val MIB = 1024 * 1024
        const val GIB = 1 shl 30

        /** Around every class edge: power-of-two classes, the 1 MiB switch, and quarter steps above it. */
        val REQUEST_SIZES =
            intArrayOf(
                1,
                2,
                3,
                1023,
                1024,
                1025,
                65_536,
                212_992,
                MIB - 1,
                MIB,
                MIB + 1,
                2 * MIB - 1,
                2 * MIB,
                2 * MIB + 1,
                2_500_000,
                3 * MIB,
                3_000_000,
                4 * MIB,
                6_291_456,
                16 * MIB + 29,
            )
    }

    @Test
    fun releasedBufferIsReusedForTheSameRequestSizeSingleThreaded() = releasedBufferIsReused(ThreadingMode.SingleThreaded)

    @Test
    fun releasedBufferIsReusedForTheSameRequestSizeMultiThreaded() = releasedBufferIsReused(ThreadingMode.MultiThreaded)

    private fun releasedBufferIsReused(threadingMode: ThreadingMode) {
        for (size in REQUEST_SIZES) {
            withPool(threadingMode = threadingMode, maxPoolSize = 4, defaultBufferSize = 1) { pool ->
                val first = pool.acquire(size)
                assertTrue(first.capacity >= size, "capacity ${first.capacity} < requested $size")
                pool.release(first)
                val second = pool.acquire(size)
                assertTrue(second.capacity >= size, "capacity ${second.capacity} < requested $size")
                assertEquals(1L, pool.stats().poolHits, "request of $size bytes must reuse its released buffer")
                pool.release(second)
            }
        }
    }

    @Test
    fun aRequestIsFiledWhereItsAllocationIsSearchedFor() {
        for (size in REQUEST_SIZES + intArrayOf(GIB - 1, GIB)) {
            val capacity = BufferSizeClass.roundUp(size)
            assertTrue(capacity >= size, "class of $size is $capacity")
            assertEquals(
                BufferSizeClass.bucketForRequest(size),
                BufferSizeClass.bucketForCapacity(capacity),
                "a buffer allocated for $size bytes must be filed where a request for $size bytes searches",
            )
        }
    }

    @Test
    fun requestsAboveOneGibAreExactAndNeverPooled() {
        for (size in intArrayOf(GIB + 1, GIB + MIB, Int.MAX_VALUE)) {
            assertEquals(size, BufferSizeClass.roundUp(size), "$size must be allocated exactly")
            assertTrue(
                BufferSizeClass.bucketForRequest(size) >= BufferSizeClass.BUCKET_COUNT,
                "$size must not search any bucket",
            )
        }
        assertTrue(BufferSizeClass.bucketForCapacity(Int.MAX_VALUE) < BufferSizeClass.BUCKET_COUNT)
    }

    private fun sweepSizes(): List<Int> {
        val sizes = ArrayList<Int>()
        for (n in 0..4096) sizes += n
        for (exponent in 10..30) {
            val base = 1 shl exponent
            for (numerator in 4..8) {
                val edge = base + (base / 4) * (numerator - 4)
                for (delta in -1..1) if (edge + delta in 0..GIB) sizes += edge + delta
            }
        }
        sizes += listOf(GIB - 1, GIB, 100 * MIB + 100_000, 600 * MIB)
        return sizes
    }

    @Test
    fun classCapacityCoversTheRequestAndIsAFixedPoint() {
        for (size in sweepSizes()) {
            val capacity = BufferSizeClass.roundUp(size)
            assertTrue(capacity >= size, "class of $size is $capacity")
            assertEquals(capacity, BufferSizeClass.roundUp(capacity), "capacity $capacity of $size must be a fixed point")
            assertEquals(
                BufferSizeClass.bucketForRequest(capacity),
                BufferSizeClass.bucketForCapacity(capacity),
                "capacity $capacity must be filed where it is searched",
            )
            if (size > MIB) assertTrue(capacity.toLong() * 4 <= size.toLong() * 5, "waste of $size -> $capacity exceeds 25%")
        }
    }

    @Test
    fun largeClassesAreQuarterStepsOfEachPowerOfTwo() {
        assertEquals(MIB + MIB / 4, BufferSizeClass.roundUp(MIB + 1))
        assertEquals(2 * MIB + MIB / 2, BufferSizeClass.roundUp(2_500_000))
        assertEquals(20 * MIB, BufferSizeClass.roundUp(16 * MIB + 29))
        assertEquals(GIB, BufferSizeClass.roundUp(GIB - 1))
        assertEquals(61, BufferSizeClass.BUCKET_COUNT)
    }
}
