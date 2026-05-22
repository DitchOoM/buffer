package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.PoolReleasable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Lifecycle invariants for [com.ditchoom.buffer.pool.TrackedSlice] — slices
 * produced by [com.ditchoom.buffer.pool.PooledBuffer.slice]. The pooled-factory
 * write path (MQTT `pooledFactoryWritePath` test) regressed on 2026-05-12 because
 * `TrackedSlice.freeNativeMemory()` resolved through `PlatformBuffer by inner`
 * to the detached slice's native-free instead of decrementing the parent's
 * refcount. Result: every slice handed out by `PooledBuffer.slice()` stranded
 * its underlying chunk in the pool forever.
 *
 * These tests pin the corrected contract:
 *
 *   1. `freeNativeMemory()` on a pooled slice is equivalent to `releaseToPool()`.
 *   2. The underlying chunk is returned to the pool only when the chunk reference
 *      AND every outstanding slice have been released — never sooner.
 *   3. Re-acquire after release returns the same underlying storage (pool reuse).
 *
 * Runs on every platform via commonTest because the bug was a delegation pitfall
 * in commonMain Kotlin, but the manifestation (RSS bloat, missed pool hits)
 * varies by backend: DirectByteBuffer on JVM, malloc on Linux native, NSData on
 * Apple, Int8Array on JS.
 */
class PooledSliceLifecycleTests {
    private fun newPool(size: Int = 1024): BufferPool = BufferPool(maxPoolSize = 4, defaultBufferSize = size)

    @Test
    fun sliceFreeNativeMemoryReleasesParentReference() {
        val pool = newPool()
        val chunk = pool.acquire(256) as PlatformBuffer
        val slice = chunk.slice()
        assertTrue(slice is PoolReleasable, "PooledBuffer.slice() must return a PoolReleasable wrapper")

        // chunk: refCount=2 (chunk + slice). Releasing only chunk → refCount=1, still alive.
        chunk.freeNativeMemory()
        assertEquals(0, pool.stats().currentPoolSize, "Chunk freed but slice still alive — pool must NOT have it back yet")

        // slice.freeNativeMemory must route through releaseToPool() so refCount→0 → pool.release.
        slice.freeNativeMemory()
        assertEquals(1, pool.stats().currentPoolSize, "After both chunk and slice released, buffer must be back in pool")

        pool.clear()
    }

    @Test
    fun chunkFreeAloneDoesNotReturnBufferWhileSliceOutstanding() {
        val pool = newPool()
        val chunk = pool.acquire(256) as PlatformBuffer
        val slice = chunk.slice()

        chunk.freeNativeMemory()
        assertEquals(0, pool.stats().currentPoolSize, "Outstanding slice must pin the chunk in use")

        // Now finish the lifecycle so we leave nothing pinned for the next test.
        slice.freeNativeMemory()
        assertEquals(1, pool.stats().currentPoolSize)
        pool.clear()
    }

    @Test
    fun sliceFreeAloneDoesNotReturnBufferWhileChunkOutstanding() {
        val pool = newPool()
        val chunk = pool.acquire(256) as PlatformBuffer
        val slice = chunk.slice()

        slice.freeNativeMemory()
        assertEquals(0, pool.stats().currentPoolSize, "Chunk still held by caller — pool must NOT have it back yet")

        chunk.freeNativeMemory()
        assertEquals(1, pool.stats().currentPoolSize)
        pool.clear()
    }

    @Test
    fun multipleOutstandingSlicesAllMustBeFreedBeforeRecycle() {
        val pool = newPool()
        val chunk = pool.acquire(256) as PlatformBuffer
        val s1 = chunk.slice()
        val s2 = chunk.slice()
        val s3 = chunk.slice()

        chunk.freeNativeMemory()
        s1.freeNativeMemory()
        s2.freeNativeMemory()
        assertEquals(0, pool.stats().currentPoolSize, "3rd slice still outstanding — buffer must not recycle")

        s3.freeNativeMemory()
        assertEquals(1, pool.stats().currentPoolSize, "After all references released, buffer recycles exactly once")
        pool.clear()
    }

    @Test
    fun nestedSliceOfSliceParticipatesInRefcounting() {
        val pool = newPool()
        val chunk = pool.acquire(256) as PlatformBuffer
        val outerSlice = chunk.slice()
        val innerSlice = outerSlice.slice()

        chunk.freeNativeMemory()
        outerSlice.freeNativeMemory()
        assertEquals(0, pool.stats().currentPoolSize, "Inner slice still outstanding — must keep chunk pinned")

        innerSlice.freeNativeMemory()
        assertEquals(1, pool.stats().currentPoolSize)
        pool.clear()
    }

    @Test
    fun releasedSliceIsIdempotent() {
        val pool = newPool()
        val chunk = pool.acquire(256) as PlatformBuffer
        val slice = chunk.slice()
        chunk.freeNativeMemory()
        slice.freeNativeMemory()
        assertEquals(1, pool.stats().currentPoolSize)

        // Double-free on the slice must NOT spuriously decrement and re-pool.
        slice.freeNativeMemory()
        assertEquals(1, pool.stats().currentPoolSize, "Double-free on slice must be idempotent")
        pool.clear()
    }

    @Test
    fun recycledChunkSurvivesAcquireReleaseCycles() {
        val pool = newPool()
        val firstChunk = pool.acquire(256) as PlatformBuffer
        val firstSlice = firstChunk.slice()
        firstChunk.freeNativeMemory()
        firstSlice.freeNativeMemory()

        val statsAfter1 = pool.stats()
        assertEquals(1, statsAfter1.currentPoolSize)

        val secondChunk = pool.acquire(256) as PlatformBuffer
        val statsAfter2 = pool.stats()
        assertEquals(0, statsAfter2.currentPoolSize, "Re-acquire must pull from pool, leaving it empty")
        assertTrue(statsAfter2.poolHits >= 1, "Second acquire must be a pool hit, not a miss")

        secondChunk.freeNativeMemory()
        assertEquals(1, pool.stats().currentPoolSize)
        pool.clear()
    }

    @Test
    fun heavyEncodeStyleChurnConvergesToFullPoolReuse() {
        // Models the FramedEncoder hot path: acquire → write → slice → free
        // both chunk and slice. After warmup, every acquire must be a pool hit.
        val pool = newPool(size = 512)
        val iterations = 200

        repeat(iterations) {
            val chunk = pool.acquire(256) as PlatformBuffer
            chunk.writeInt(0xCAFEBABE.toInt())
            val slice = chunk.slice()
            chunk.freeNativeMemory()
            slice.freeNativeMemory()
        }

        val stats = pool.stats()
        // First iteration is a miss; from #2 onward every acquire reuses.
        assertEquals(iterations.toLong(), stats.totalAllocations)
        assertEquals(1L, stats.poolMisses, "Only the very first acquire should miss the pool")
        assertEquals((iterations - 1).toLong(), stats.poolHits)
        assertEquals(1.0, stats.poolHits.toDouble() / iterations, 0.01)
        assertNotEquals(0, stats.currentPoolSize, "Pool must hold the recycled buffer between iterations")
        pool.clear()
    }
}
