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
 * Model checks [PooledBuffer]'s reference count under a [ThreadingMode.MultiThreaded] pool.
 *
 * ## Why this exists alongside [PooledBufferConcurrencyStressTest]
 *
 * That test's own documentation is the argument for this one. It records that the obvious shape —
 * a barrier-synchronized release per chunk — "needs genuine luck even across dozens of threads and
 * thousands of trials," and that a 24-thread x 5,000-trial run once passed clean against the known
 * broken build. It works around that with sustained churn: 16 threads x 150,000 cycles, chosen so a
 * lost update "stops being a matter of luck."
 *
 * That is sampling. It answers "did 2.4M random interleavings happen to hit the bug," and the honest
 * answer to a clean run is "not this time." Lincheck answers a different question — "does an
 * interleaving exist" — by enumerating them under a deterministic scheduler and, on failure,
 * shrinking to the shortest schedule that reproduces it. A counterexample is a printed sequence of
 * numbered steps, not a seed to re-run under load.
 *
 * The two are complementary and both belong: the stress test runs the real racing hardware over the
 * real allocator, this one proves the state machine has no bad interleaving at all.
 *
 * ## The invariant
 *
 * A chunk is returned to its pool exactly once, and a reference may never be taken after the last
 * one is dropped. [PooledBuffer.addRef] is meant to enforce the second half by refusing
 * resurrection, so sequentially every `addRef()` on a fully-released chunk returns `refused`. Every
 * operation here reports `ok`/`refused` rather than throwing, so that refusal is a *return value*
 * Lincheck can compare against the sequential specification — an interleaving in which a caller gets
 * `ok` where every sequential ordering says `refused` is a reference handed out onto storage the
 * pool has already reclaimed, which is [#374](https://github.com/DitchOoM/buffer/issues/374)'s
 * failure mode reached by a different route.
 *
 * Allocation goes through [BufferFactory.managed] and `maxPoolSize = 0`, exactly as the stress test
 * does and for the same reason: `release()` then calls `freeNativeMemory()` immediately instead of
 * pushing to the freelist, so counting those calls counts "the pool believed the last reference was
 * dropped" — without a real double free, which would crash the JVM instead of failing an assertion.
 */
class PooledBufferLincheckTest {
    private val factory = ReleaseCountingFactory(BufferFactory.managed())

    private val pool =
        BufferPool(
            threadingMode = ThreadingMode.MultiThreaded,
            maxPoolSize = 0,
            defaultBufferSize = 64,
            factory = factory,
        )

    /** Starts at refCount = 1, the chunk's own reference. */
    private val chunk = pool.acquire(64) as PooledBuffer

    @Operation
    fun addRef(): String =
        try {
            chunk.addRef()
            "ok"
        } catch (expected: IllegalStateException) {
            "refused"
        }

    @Operation
    fun releaseRef(): String =
        try {
            chunk.releaseRef()
            "ok"
        } catch (expected: IllegalStateException) {
            "refused"
        }

    /**
     * Holds after every actor: no interleaving may convince the pool that this chunk's last
     * reference was dropped more than once. A second release hands the same backing storage to two
     * owners — the double-release half of #374.
     */
    @Validate
    fun theChunkIsReturnedToThePoolAtMostOnce() {
        val releases = factory.releaseCount()
        check(releases <= 1) {
            "the pool was told this chunk's last reference was dropped $releases times; " +
                "the chunk may be returned at most once"
        }
    }

    @Test
    fun refCountIsLinearizable() {
        ModelCheckingOptions()
            .threads(2)
            .actorsPerThread(2)
            .actorsBefore(1)
            .actorsAfter(0)
            .iterations(200)
            .invocationsPerIteration(5_000)
            // The scenario the random generator has to stumble onto: drop the chunk's own and only
            // reference, then race two callers into addRef() on the now-dead chunk. Pinned so a
            // regression fails on the first iteration rather than whenever generation happens to
            // reproduce it.
            .addCustomScenario {
                initial {
                    actor(::releaseRef)
                }
                parallel {
                    thread { actor(::addRef) }
                    thread { actor(::addRef) }
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
