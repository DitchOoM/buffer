package com.ditchoom.buffer

import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.pool.withPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for allocator fragmentation caused by pool churn (Autobahn case
 * 9.2.5 OOM on Android/ART, CI run 28618587934 — see ANDROID_ART_ALLOCATOR.md).
 *
 * The failure mechanism was pool-shaped: every acquire() of a unique odd size was a pool
 * miss that allocated the exact odd size, and every wrong-sized pop freed a buffer just
 * to reallocate — so MB-scale message churn produced an unbounded stream of unique-sized
 * allocations that permanently fragmented ART's freelist spaces.
 *
 * These tests pin the two pool-level properties that stop that churn on every platform:
 * size-class rounding (bounded distinct allocation sizes) and size-aware reuse (misses
 * bounded by the number of distinct size classes, not by the number of acquires).
 */
class BufferPoolFragmentationTests {
    private companion object {
        const val KIB = 1024

        /** KiB-scale stand-ins for the Autobahn message sizes (8 KiB chops + MB messages). */
        val CHURN_SIZES_KIB = intArrayOf(3, 5, 9, 17)
        const val ROUNDS = 20
    }

    /**
     * Mixed-size churn where no exact size ever repeats (odd per-round offsets, like
     * assembled network messages). Misses must stay bounded by the number of distinct
     * size classes; the old exact-size pool missed (and freed + reallocated) on most
     * acquires of this pattern.
     */
    @Test
    fun mixedSizeChurnHasBoundedMissesSingleThreaded() = mixedSizeChurnHasBoundedMisses(ThreadingMode.SingleThreaded)

    @Test
    fun mixedSizeChurnHasBoundedMissesMultiThreaded() = mixedSizeChurnHasBoundedMisses(ThreadingMode.MultiThreaded)

    private fun mixedSizeChurnHasBoundedMisses(threadingMode: ThreadingMode) =
        withPool(
            threadingMode = threadingMode,
            maxPoolSize = 8,
            defaultBufferSize = 1 * KIB,
        ) { pool ->
            repeat(ROUNDS) { round ->
                val held = ArrayList<ReadWriteBuffer>()
                for (kib in CHURN_SIZES_KIB) {
                    // Unique odd size every single acquire across all rounds
                    val buffer = pool.acquire(kib * KIB + round * 7 + 1)
                    buffer.writeByte(round.toByte())
                    held += buffer
                }
                held.forEach { pool.release(it) }
            }

            val stats = pool.stats()
            assertEquals(
                (ROUNDS * CHURN_SIZES_KIB.size).toLong(),
                stats.totalAllocations,
                "sanity: every acquire counted",
            )
            assertTrue(
                stats.poolMisses <= CHURN_SIZES_KIB.size.toLong(),
                "misses must be bounded by the number of distinct size classes " +
                    "(${CHURN_SIZES_KIB.size}), got ${stats.poolMisses} " +
                    "(hits=${stats.poolHits}, hitRate=${stats.hitRate})",
            )
            assertTrue(
                stats.hitRate >= 0.9,
                "size-aware reuse must keep the hit rate high under mixed-size churn, " +
                    "got ${stats.hitRate}",
            )
        }

    /**
     * Interleaved small + growing large acquires — the exact shape of the websocket
     * read path that crashed CI (8 KiB chops held mid-frame while a large odd-sized
     * assembled-message buffer is acquired).
     */
    @Test
    fun interleavedChopAndLargeChurnHasBoundedMisses() =
        withPool(
            threadingMode = ThreadingMode.MultiThreaded,
            maxPoolSize = 16,
            defaultBufferSize = 1 * KIB,
        ) { pool ->
            repeat(ROUNDS) { round ->
                val chops = ArrayList<ReadWriteBuffer>()
                repeat(4) { chops += pool.acquire(1 * KIB) }
                val big = pool.acquire(29 * KIB + round * 13 + 29)
                big.writeByte(0)
                chops.forEach { pool.release(it) }
                pool.release(big)
            }

            val stats = pool.stats()
            // One class for the chops (1 KiB) + one for the big buffers (32 KiB class),
            // with 4 chop buffers live at once → at most 4 + 1 initial misses.
            assertTrue(
                stats.poolMisses <= 5,
                "expected at most 5 misses (4 concurrent chop buffers + 1 large class), " +
                    "got ${stats.poolMisses} (hits=${stats.poolHits})",
            )
        }

    /** Acquire sizes are rounded up to their power-of-two size class. */
    @Test
    fun acquireRoundsUpToSizeClass() =
        withPool(maxPoolSize = 4, defaultBufferSize = 1 * KIB) { pool ->
            val oddSize = 3 * KIB + 5
            val buffer = pool.acquire(oddSize)
            assertEquals(
                4 * KIB,
                buffer.capacity,
                "an odd-sized acquire must allocate its power-of-two size class, " +
                    "not the exact odd size",
            )
            pool.release(buffer)

            // A different odd size in the same class must reuse the same buffer
            val second = pool.acquire(3 * KIB + 999)
            assertEquals(4 * KIB, second.capacity)
            assertEquals(1L, pool.stats().poolHits, "same size class must be a pool hit")
            pool.release(second)
        }

    /** Power-of-two requests must not be doubled by the rounding. */
    @Test
    fun acquireExactPowerOfTwoIsNotRoundedUp() =
        withPool(maxPoolSize = 4, defaultBufferSize = 1 * KIB) { pool ->
            val buffer = pool.acquire(8 * KIB)
            assertEquals(8 * KIB, buffer.capacity)
            pool.release(buffer)
        }

    /**
     * Above 1 MiB, rounding switches to 1 MiB multiples: power-of-two rounding would
     * double large-message allocations (16 MiB + 29 → 32 MiB), enough to OOM
     * heap-constrained Android under Autobahn's 16 MB echo cases (9.1.6). 1 MiB
     * multiples cap the waste while keeping block sizes commensurable.
     */
    @Test
    fun largeAcquireRoundsToMebibyteMultipleNotPowerOfTwo() {
        val mib = 1024 * KIB
        withPool(maxPoolSize = 4, defaultBufferSize = 1 * KIB) { pool ->
            val buffer = pool.acquire(16 * mib + 29)
            assertEquals(
                17 * mib,
                buffer.capacity,
                "a large odd-sized acquire must round to the next 1 MiB multiple, " +
                    "not the next power of two",
            )
            pool.release(buffer)
        }
        withPool(maxPoolSize = 4, defaultBufferSize = 1 * KIB) { pool ->
            val exact = pool.acquire(4 * mib)
            assertEquals(4 * mib, exact.capacity, "1 MiB-multiple requests must not be rounded")
            pool.release(exact)
        }
    }
}
