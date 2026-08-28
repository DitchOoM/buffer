package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.PooledBuffer
import com.ditchoom.buffer.pool.ThreadingMode
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Validate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * Model checks [PooledBuffer.slice] — the operation [PooledBufferLincheckTest] does not cover.
 *
 * ## Why slice needs its own check
 *
 * That test models `addRef` and `releaseRef` as primitives. `slice()` is neither: it is
 * `checkNotFreed()` then `addRef()` then a `TrackedSlice` wrapping the parent, and the slice's
 * `freeNativeMemory()` reaches back into `parent.releaseRef()`. So a slice is a *compound*
 * retain/release whose two halves are separated by however long a consumer holds the slice — which
 * is exactly the window a concurrent release of the parent lands in.
 *
 * It is also the shape multi-consumer reads take. `asReadBuffer()` hands back the handle's one
 * buffer with one cursor, so anything wanting several readers over the same pooled bytes takes a
 * slice per reader. Those readers are typically on different threads, and they finish in an order
 * nobody controls. That is the interleaving space this enumerates.
 *
 * ## The invariant
 *
 * A chunk is acquired with refCount = 1 — the acquirer's own reference. Slices only ever add
 * references on top of it. **So no sequence of slice-taking and slice-releasing may return the chunk
 * to the pool**, because the acquirer has not dropped its reference. If it is returned anyway, the
 * pool has handed the same storage to a second owner while the first still holds it, and the next
 * read through the original reference is a use-after-free.
 *
 * That invariant is what makes the scenarios below meaningful without any `@Operation` having to
 * release the chunk's own reference: the expected release count is not "at most one", it is **zero**.
 *
 * As in [PooledBufferLincheckTest], allocation goes through [BufferFactory.managed] with
 * `maxPoolSize = 0` so `release()` calls `freeNativeMemory()` immediately — counting those counts
 * "the pool believed the last reference was dropped", without performing a real double free that
 * would crash the JVM rather than fail an assertion. Every operation reports `ok`/`refused` instead
 * of throwing, so a refusal is a return value Lincheck can hold against the sequential spec.
 */
class PooledBufferSliceLincheckTest {
    private val factory = ReleaseCountingFactory(BufferFactory.managed())

    private val pool =
        BufferPool(
            threadingMode = ThreadingMode.MultiThreaded,
            maxPoolSize = 0,
            defaultBufferSize = 64,
            factory = factory,
        )

    /** Starts at refCount = 1 — the acquirer's reference, never dropped by any operation here. */
    private val chunk = pool.acquire(64) as PooledBuffer

    /** Slices taken and not yet released, standing in for consumers still reading. */
    private val live = ConcurrentLinkedQueue<PlatformBuffer>()

    @Operation
    fun takeSlice(): String =
        try {
            live.add(chunk.slice())
            "ok"
        } catch (expected: IllegalStateException) {
            "refused"
        }

    @Operation
    fun releaseSlice(): String {
        val slice = live.poll() ?: return "none"
        return try {
            slice.freeNativeMemory()
            "ok"
        } catch (expected: IllegalStateException) {
            "refused"
        }
    }

    /**
     * Holds after every actor. The acquirer's own reference is outstanding for the whole scenario,
     * so the chunk must never come back to the pool no matter how the slice retains and releases
     * interleave.
     */
    @Validate
    fun theChunkIsNeverReturnedWhileTheAcquirerHoldsIt() {
        val releases = factory.releaseCount()
        check(releases == 0) {
            "the pool was told this chunk's last reference was dropped $releases time(s), but the " +
                "acquirer never released its own reference — the chunk was handed to a second owner"
        }
    }

    @Test
    fun sliceRetainAndReleaseAreLinearizable() {
        ModelCheckingOptions()
            .threads(2)
            .actorsPerThread(2)
            .actorsBefore(1)
            .actorsAfter(0)
            .iterations(200)
            .invocationsPerIteration(5_000)
            // The fan-out shape, pinned rather than left to the generator: one consumer takes a
            // slice while another releases the slice it already holds. The retain of one races the
            // release of the other against a single refcount.
            .addCustomScenario {
                initial {
                    actor(::takeSlice)
                }
                parallel {
                    thread { actor(::takeSlice) }
                    thread { actor(::releaseSlice) }
                }
            }
            // Two consumers finishing at once — the last two references dropping concurrently,
            // which is where a lost decrement shows up as a chunk returned early.
            .addCustomScenario {
                initial {
                    actor(::takeSlice)
                    actor(::takeSlice)
                }
                parallel {
                    thread { actor(::releaseSlice) }
                    thread { actor(::releaseSlice) }
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
