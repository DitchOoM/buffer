package com.ditchoom.buffer.pool

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.CloseableBuffer
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ExperimentalFanoutApi
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministicAllocateOrSkip
import com.ditchoom.buffer.unwrapFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Lifetime and concurrency invariants for [SharedBytes] — the encode-once fan-out primitive.
 *
 * The three failure modes these tests exist to make impossible:
 *
 *  1. **Free too early.** Storage returns to its pool (or has its native memory released) while a
 *     fan-out consumer still holds a reference, so the consumer reads memory another acquirer now
 *     owns. Pinned by asserting the pool observable stays empty for the whole race.
 *  2. **Free twice / never.** [PooledBuffer]'s own refcount is deliberately non-atomic; `SharedBytes`
 *     carries the atomic count instead, and its 1→0 transition must be observed by exactly one
 *     thread. Pinned by count arithmetic plus loud failure on over-release.
 *  3. **Resurrect from zero.** A blind `refCount++` in [SharedBytes.retain] would revive a freed
 *     buffer; the CAS loop must refuse instead.
 *
 * Runs on every platform via commonTest: the count is common Kotlin, but the free at zero lands on
 * a different discipline per backend (pool return, `Arena`/`free()`, ARC, GC no-op) and the view
 * path slices a different concrete buffer type on each.
 */
@OptIn(ExperimentalFanoutApi::class)
class SharedBytesTests {
    // ========================================================================
    // 1. adopt -> release frees exactly once
    // ========================================================================

    @Test
    fun pooledStorageReturnsToItsPoolExactlyOnceAtZero() {
        val pool = newPool()
        val shared = SharedBytes.adopt(pooledPayload(pool))

        assertEquals(PAYLOAD_SIZE, shared.size, "size is the readable byte count, not the capacity")
        assertEquals(0, pool.stats().currentPoolSize, "the creator's reference must pin the chunk")

        shared.release()
        assertEquals(1, pool.stats().currentPoolSize, "the last release must return the chunk to its pool")

        // "Exactly once" is the whole point: a duplicate release is an accounting bug and must fail
        // loudly rather than push the same chunk onto the freelist a second time.
        assertFailsWith<IllegalStateException> { shared.release() }
        assertEquals(1, pool.stats().currentPoolSize, "over-release must not re-pool the chunk")

        pool.clear()
    }

    @Test
    fun deterministicStorageIsFreedOnlyWhenTheLastReferenceDrops() {
        val buffer = deterministicAllocateOrSkip(PAYLOAD_SIZE) ?: return
        val closeable = buffer as? CloseableBuffer
        if (closeable == null) {
            // Apple's deterministic buffer is ARC-managed and exposes no freed observable; the
            // pooled tests cover the free-at-zero contract on that platform.
            buffer.freeNativeMemory()
            return
        }
        fillPattern(buffer)

        val shared = SharedBytes.adopt(buffer)
        shared.retain()
        shared.release()
        assertFalse(closeable.isFreed, "one outstanding reference must keep native memory alive")
        assertPattern(shared.view(), "view while still referenced")

        shared.release()
        assertTrue(closeable.isFreed, "the last release must free the native memory")
    }

    // ========================================================================
    // 2. retain/release balance
    // ========================================================================

    @Test
    fun nRetainsRequireNPlusOneReleases() {
        val pool = newPool()
        val shared = SharedBytes.adopt(pooledPayload(pool))
        val extraReferences = 5

        repeat(extraReferences) { assertSame(shared, shared.retain(), "retain() returns this for chaining") }
        repeat(extraReferences) { shared.release() }
        assertEquals(
            0,
            pool.stats().currentPoolSize,
            "N retains balanced by N releases still leaves the creator's reference outstanding",
        )

        shared.release()
        assertEquals(1, pool.stats().currentPoolSize, "the N+1th release is the one that frees")
        pool.clear()
    }

    @Test
    fun overReleaseThrows() {
        val pool = newPool()
        val shared = SharedBytes.adopt(pooledPayload(pool))
        shared.release()

        assertFailsWith<IllegalStateException> { shared.release() }
        pool.clear()
    }

    @Test
    fun retainAfterZeroThrowsInsteadOfResurrecting() {
        val pool = newPool()
        val shared = SharedBytes.adopt(pooledPayload(pool))
        shared.release()

        assertFailsWith<IllegalStateException> { shared.retain() }
        assertEquals(
            1,
            pool.stats().currentPoolSize,
            "a refused retain must not disturb the already-returned chunk",
        )
        pool.clear()
    }

    @Test
    fun viewAfterZeroThrows() {
        val pool = newPool()
        val shared = SharedBytes.adopt(pooledPayload(pool))
        shared.release()

        assertFailsWith<IllegalStateException> { shared.view() }
        pool.clear()
    }

    // ========================================================================
    // 3. Concurrent stress
    // ========================================================================

    @Test
    fun racedRetainReleasePairsNeverFreeEarlyAndFreeExactlyOnce() =
        runTest {
            val pool = newPool()
            val shared = SharedBytes.adopt(pooledPayload(pool))

            withContext(Dispatchers.Default) {
                coroutineScope {
                    repeat(WORKERS) {
                        launch {
                            repeat(ITERATIONS) { iteration ->
                                shared.retain()
                                assertPattern(shared.view(), "raced view")
                                shared.release()
                                if (iteration % POOL_PROBE_INTERVAL == 0) {
                                    assertEquals(
                                        0,
                                        pool.stats().currentPoolSize,
                                        "the base reference must keep the chunk out of the pool for the whole race",
                                    )
                                }
                            }
                        }
                    }
                }
            }

            assertEquals(
                0,
                pool.stats().currentPoolSize,
                "${WORKERS * ITERATIONS} balanced retain/release pairs must not free anything",
            )
            // The count is exactly 1 here: retain() only succeeds above zero, and the release that
            // follows must be the one that frees.
            shared.retain()
            shared.release()
            assertEquals(0, pool.stats().currentPoolSize)
            shared.release()
            assertEquals(1, pool.stats().currentPoolSize, "the final release frees, exactly once")
            assertFailsWith<IllegalStateException> { shared.release() }

            pool.clear()
        }

    @Test
    fun concurrentViewsReadDisjointRegionsCorrectly() =
        runTest {
            val pool = newPool()
            val shared = SharedBytes.adopt(pooledPayload(pool))
            val region = PAYLOAD_SIZE / WORKERS

            withContext(Dispatchers.Default) {
                coroutineScope {
                    repeat(WORKERS) { worker ->
                        launch {
                            val start = worker * region
                            repeat(ITERATIONS) {
                                // Every worker builds its own view concurrently with the others and
                                // walks only its own window — the cursors must never interfere.
                                val view = shared.view()
                                view.position(start)
                                for (i in start until start + region) {
                                    assertEquals(patternByte(i), view.readByte(), "worker $worker byte $i")
                                }
                                assertEquals(PAYLOAD_SIZE - start - region, view.remaining())
                            }
                        }
                    }
                }
            }

            shared.release()
            assertEquals(1, pool.stats().currentPoolSize)
            pool.clear()
        }

    // ========================================================================
    // 4. View independence
    // ========================================================================

    @Test
    fun viewsAdvanceIndependentCursorsOverTheSameBytes() {
        val buffer = BufferFactory.Default.allocate(PAYLOAD_SIZE)
        fillPattern(buffer)
        val shared = SharedBytes.adopt(buffer)

        val first = shared.view()
        val second = shared.view()
        assertEquals(PAYLOAD_SIZE, first.remaining())
        assertEquals(PAYLOAD_SIZE, second.remaining())

        // Drain the first view halfway; the second must be untouched.
        repeat(PAYLOAD_SIZE / 2) { i -> assertEquals(patternByte(i), first.readByte()) }
        assertEquals(PAYLOAD_SIZE / 2, first.position(), "first view advanced")
        assertEquals(0, second.position(), "second view must not see the first view's cursor")
        assertEquals(PAYLOAD_SIZE, second.remaining())

        // Both views still read the full, correct content from their own positions.
        for (i in PAYLOAD_SIZE / 2 until PAYLOAD_SIZE) {
            assertEquals(patternByte(i), first.readByte(), "first view tail byte $i")
        }
        assertPattern(second, "second view read in full afterwards")

        // A third view minted after the other two are drained still starts at the beginning.
        assertPattern(shared.view(), "third view")
        shared.release()
    }

    @Test
    fun viewsAreByteIdenticalToTheAdoptedSource() {
        val source = BufferFactory.Default.allocate(PAYLOAD_SIZE)
        fillPattern(source)
        val expected = ByteArray(PAYLOAD_SIZE) { patternByte(it) }

        val shared = SharedBytes.adopt(source)
        val view = shared.view()
        assertEquals(expected.size, view.remaining())
        assertTrue(
            view.contentEquals(BufferFactory.Default.wrap(expected)),
            "a view must expose exactly the adopted bytes",
        )
        // contentEquals is non-consuming, so the same view still reads the same bytes byte-by-byte.
        assertPattern(view, "view after contentEquals")
        shared.release()
    }

    // ========================================================================
    // 5. Pooled path
    // ========================================================================

    @Test
    fun pooledStorageIsReAcquirableOnlyAfterTheFinalRelease() {
        val pool = newPool()
        val chunk = pooledPayload(pool)
        val storage = chunk.unwrapFully()

        val shared = SharedBytes.adopt(chunk)
        shared.retain() // a fan-out consumer's reference

        // Before the final release the chunk is still checked out and views read correctly.
        assertEquals(0, pool.stats().currentPoolSize)
        assertPattern(shared.view(), "view while the chunk is checked out")

        shared.release()
        assertEquals(0, pool.stats().currentPoolSize, "one reference left — the chunk stays checked out")
        assertPattern(shared.view(), "view with the last reference still held")

        shared.release()
        assertEquals(1, pool.stats().currentPoolSize, "the chunk is back in the pool")

        val reacquired = pool.acquire(PAYLOAD_SIZE) as PlatformBuffer
        assertSame(storage, reacquired.unwrapFully(), "the pool must hand back the very same storage")
        assertTrue(pool.stats().poolHits >= 1, "the re-acquire must be a pool hit, not a fresh allocation")

        reacquired.freeNativeMemory()
        pool.clear()
    }

    @Test
    fun adoptingAPooledChunkDoesNotDisturbTheWrappersOwnRefcount() {
        // view() slices the UNWRAPPED buffer on purpose, so it must not bump PooledBuffer's
        // non-atomic per-slice count — if it did, the chunk would be pinned forever by views that
        // nobody can release (view() hands back a read-only type with no release channel).
        val pool = newPool()
        val shared = SharedBytes.adopt(pooledPayload(pool))
        repeat(16) { assertPattern(shared.view(), "view $it") }

        shared.release()
        assertEquals(1, pool.stats().currentPoolSize, "outstanding views must not pin the chunk")
        pool.clear()
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun newPool(): BufferPool =
        BufferPool(
            threadingMode = ThreadingMode.MultiThreaded,
            maxPoolSize = 4,
            defaultBufferSize = POOL_BUFFER_SIZE,
            factory = BufferFactory.Default,
        )

    /** A pool-acquired chunk holding [PAYLOAD_SIZE] pattern bytes, positioned for reading. */
    private fun pooledPayload(pool: BufferPool): PlatformBuffer {
        val chunk = pool.acquire(PAYLOAD_SIZE) as PlatformBuffer
        fillPattern(chunk)
        return chunk
    }

    private fun fillPattern(buffer: PlatformBuffer) {
        for (i in 0 until PAYLOAD_SIZE) buffer.writeByte(patternByte(i))
        buffer.resetForRead()
    }

    private fun assertPattern(
        view: ReadBuffer,
        message: String,
    ) {
        assertEquals(PAYLOAD_SIZE, view.remaining(), "$message: remaining")
        for (i in 0 until PAYLOAD_SIZE) {
            assertEquals(patternByte(i), view.readByte(), "$message: byte $i")
        }
    }

    private companion object {
        const val PAYLOAD_SIZE = 64
        const val POOL_BUFFER_SIZE = 256
        const val WORKERS = 4
        const val ITERATIONS = 1000
        const val POOL_PROBE_INTERVAL = 64

        fun patternByte(index: Int): Byte = (index * 31 + 7).toByte()
    }
}
