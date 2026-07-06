package com.ditchoom.buffer

import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.pool.createBufferPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The pools recover from a factory [OutOfMemoryError] by draining their cached buffers,
 * hinting a GC cycle, and retrying once — the Android/ART fragmentation mitigation from
 * ANDROID_ART_ALLOCATOR.md. A second consecutive failure propagates unchanged.
 *
 * Lives in jvmCommonTest (JVM + Android): the retry path only exists where a factory allocation
 * can throw [OutOfMemoryError], and that type is JVM/Native-only — it doesn't resolve on JS/Wasm,
 * where `allocateOrReclaim` is a passthrough. This is exactly the platform the fix targets.
 */
class PoolOomResilienceTests {
    /** Delegates to a real factory but throws [OutOfMemoryError] for the first [failuresRemaining] allocations. */
    private class FlakyFactory(
        var failuresRemaining: Int,
        private val delegate: BufferFactory = BufferFactory.managed(),
    ) : BufferFactory {
        var allocateCalls = 0
            private set

        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer {
            allocateCalls++
            if (failuresRemaining > 0) {
                failuresRemaining--
                throw OutOfMemoryError("simulated OOM for $size bytes")
            }
            return delegate.allocate(size, byteOrder)
        }

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = delegate.wrap(array, byteOrder)
    }

    @Test
    fun retryAfterSingleOomSucceedsAndDrainsPool() {
        for (mode in ThreadingMode.entries) {
            val factory = FlakyFactory(failuresRemaining = 0)
            val pool = createBufferPool(mode, maxPoolSize = 8, defaultBufferSize = POOL_BUFFER_SIZE, factory = factory)

            // Seed one pooled buffer so the reclaim path has something to drain.
            pool.release(pool.acquire(POOL_BUFFER_SIZE))
            assertEquals(1, pool.stats().currentPoolSize, "$mode: seed")

            // A larger request misses the pooled buffer and hits the factory; make that
            // allocation OOM once so the retry path runs.
            factory.failuresRemaining = 1
            val buffer = pool.acquire(LARGER_REQUEST_SIZE)

            assertNotNull(buffer, "$mode: acquire recovered from OOM")
            assertTrue(buffer.capacity >= LARGER_REQUEST_SIZE, "$mode: capacity")
            assertEquals(EXPECTED_ALLOCATE_CALLS, factory.allocateCalls, "$mode: seed + failed attempt + retry")
            assertEquals(0, pool.stats().currentPoolSize, "$mode: clear() drained the pool during reclaim")
        }
    }

    @Test
    fun secondConsecutiveOomPropagates() {
        for (mode in ThreadingMode.entries) {
            val factory = FlakyFactory(failuresRemaining = 2)
            val pool = createBufferPool(mode, defaultBufferSize = POOL_BUFFER_SIZE, factory = factory)

            assertFailsWith<OutOfMemoryError>("$mode: second failure propagates") {
                pool.acquire(POOL_BUFFER_SIZE)
            }
            assertEquals(2, factory.allocateCalls, "$mode: original attempt + one retry, then give up")
        }
    }

    private companion object {
        const val POOL_BUFFER_SIZE = 1024
        const val LARGER_REQUEST_SIZE = 4096

        // Seed miss + the failed first attempt + the successful retry.
        const val EXPECTED_ALLOCATE_CALLS = 3
    }
}
