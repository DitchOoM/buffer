package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.PooledBuffer
import com.ditchoom.buffer.pool.ThreadingMode
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Validate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * Model checks the idempotence of releasing **one** slice from more than one thread.
 *
 * ## Why this is asked separately
 *
 * [PooledBuffer] makes its own double-free guard atomic on a `MultiThreaded` pool: `freeNativeMemory`
 * does `sharedFreed.exchange(1)` and only the caller that observes the 0 -> 1 transition drops the
 * reference. So freeing the same pooled buffer twice, concurrently, releases once.
 *
 * `TrackedSlice` does the same job with a plain `var released`, on every threading mode. That
 * asymmetry may be deliberate — a slice is normally owned by exactly one consumer, and freeing one
 * object twice is a caller bug in any case — but it is worth knowing rather than assuming, because
 * the shape it appears in is not exotic: a fan-out hands a slice to a consumer, and cleanup paths
 * that "free if not already freed" are a common way for a second release to arrive from a different
 * thread than the first.
 *
 * ## The invariant
 *
 * The chunk is acquired with refCount = 1 and the acquirer never drops it. One slice is taken before
 * the parallel part, so the count is 2. Releasing that one slice — however many times, from however
 * many threads — may drop at most the one reference the slice holds, leaving the acquirer's. **So the
 * chunk must never be returned to the pool.**
 *
 * If a lost update lets two threads both pass the `released` guard, `parent.releaseRef()` runs twice,
 * the count goes 2 -> 0, and the pool takes the chunk back while the acquirer is still holding it —
 * a use-after-free waiting for the acquirer's next read.
 *
 * A passing run means no such interleaving exists under the model checker, which is the answer worth
 * having either way.
 */
class TrackedSliceDoubleReleaseLincheckTest {
    private val factory = ReleaseCountingFactory(BufferFactory.managed())

    private val pool =
        BufferPool(
            threadingMode = ThreadingMode.MultiThreaded,
            maxPoolSize = 0,
            defaultBufferSize = 64,
            factory = factory,
        )

    /** refCount = 1 (the acquirer's own reference, never dropped here). */
    private val chunk = pool.acquire(64) as PooledBuffer

    /** One slice, taken up front, so refCount = 2 and every operation targets the SAME object. */
    private val slice: PlatformBuffer = chunk.slice()

    @Operation
    fun releaseTheSlice(): String =
        try {
            slice.freeNativeMemory()
            "ok"
        } catch (expected: IllegalStateException) {
            "refused"
        }

    /**
     * The acquirer's reference is outstanding throughout, so no amount of releasing this one slice
     * may hand the chunk back to the pool.
     */
    @Validate
    fun theChunkIsNeverReturnedWhileTheAcquirerHoldsIt() {
        val releases = factory.releaseCount()
        check(releases == 0) {
            "releasing one slice returned the chunk to the pool $releases time(s) while the " +
                "acquirer still held its own reference — the slice's release was counted twice"
        }
    }

    @Test
    fun releasingOneSliceTwiceDropsAtMostOneReference() {
        ModelCheckingOptions()
            .threads(2)
            .actorsPerThread(1)
            .actorsBefore(0)
            .actorsAfter(0)
            .iterations(200)
            .invocationsPerIteration(5_000)
            // Pinned: two threads free the same slice with nothing else happening. This is the
            // whole question, so it should not depend on the generator producing it.
            .addCustomScenario {
                parallel {
                    thread { actor(::releaseTheSlice) }
                    thread { actor(::releaseTheSlice) }
                }
            }.check(this::class)
    }

    /** Counts `freeNativeMemory()` calls on the raw buffer the pool allocated. */
    private class ReleaseCountingBuffer(
        private val inner: PlatformBuffer,
    ) : PlatformBuffer by inner {
        val releases = AtomicInteger(0)

        override fun freeNativeMemory() {
            releases.incrementAndGet()
            inner.freeNativeMemory()
        }
    }

    private class ReleaseCountingFactory(
        private val delegate: BufferFactory,
    ) : BufferFactory {
        @Volatile
        private var lastAllocated: ReleaseCountingBuffer? = null

        fun releaseCount(): Int = lastAllocated?.releases?.get() ?: 0

        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer {
            val wrapped = ReleaseCountingBuffer(delegate.allocate(size, byteOrder))
            lastAllocated = wrapped
            return wrapped
        }

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = delegate.wrap(array, byteOrder)
    }
}
