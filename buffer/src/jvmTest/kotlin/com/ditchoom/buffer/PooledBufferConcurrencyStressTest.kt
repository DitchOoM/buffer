package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Real-thread stress test for `PooledBuffer`'s reference count under a
 * [ThreadingMode.MultiThreaded] [BufferPool].
 *
 * `PooledBuffer.addRef()`/`releaseRef()` used to mutate a plain, non-atomic `Int` field. A
 * `MultiThreaded` pool ([com.ditchoom.buffer.pool.LockFreeBufferPool]) is thread-safe in its
 * freelist (a Treiber stack) but that says nothing about the per-buffer refcount `PooledBuffer`
 * wraps around it — nothing prevented two threads from racing `refCount++` / `--refCount`. socket
 * issue #401 hit this in production: a QUIC echo test received glibc tcache pointers instead of
 * the payload it sent, reproducible 2-for-2 on CI under load. That is the signature of a genuine
 * double-release-then-native-free: a lost update on a plain `Int` lets two threads both read the
 * same pre-decrement value, both conclude "this was the last reference", and both call
 * `pool.release()` on the same chunk — which the pool then hands out to two owners at once. (The
 * mirror failure — a lost decrement that leaves the count permanently nonzero — is a leak
 * instead; this test treats either outcome as proof of the race.)
 *
 * A single barrier-synchronized release per chunk (each thread decrements once, all released at
 * the same instant) turns out to be a poor way to *reliably* provoke this: the read-modify-write
 * sequence for a plain `Int` field is only a few machine instructions, so a single collision needs
 * genuine luck even across dozens of threads and thousands of trials — proven flaky while
 * developing this test (a run at 24 threads x 5,000 trials once passed clean against the
 * plain-counter build). Instead this sustains **continuous, unsynchronized churn**: many threads
 * loop `slice()` (`addRef`) + `freeNativeMemory()` (`releaseRef`) on the same [PooledBuffer]
 * concurrently for hundreds of thousands of cycles. Given enough concurrent unsynchronized RMW
 * traffic on one field, a lost update stops being a matter of luck — this is the textbook way to
 * demonstrate a non-atomic counter race (cf. *Java Concurrency in Practice*'s unsafe-counter
 * example), and it reproduces this bug close to 100% of runs against the plain counter (verified
 * below).
 *
 * This test intentionally never touches real native memory: it allocates through
 * [BufferFactory.managed], whose `freeNativeMemory()` is a GC no-op. A genuine double free of
 * *native* memory here could crash the JVM outright instead of failing an assertion, which would
 * defeat the point of a test that must be run — as part of proving it actually detects the bug —
 * against the pre-fix plain counter. Instead, [FreeCountingFactory] wraps the raw buffer the pool
 * allocates so `freeNativeMemory()` calls on it are countable, and `maxPoolSize = 0` forces
 * [com.ditchoom.buffer.pool.LockFreeBufferPool.release] to call it immediately on every release
 * (never push to the freelist) — so counting those calls is exactly counting how many times the
 * pool believed this chunk's last reference had been dropped.
 */
class PooledBufferConcurrencyStressTest {
    private val workerCount = 16
    private val executor: ExecutorService = Executors.newFixedThreadPool(workerCount)

    @AfterTest
    fun shutdown() {
        executor.shutdownNow()
    }

    @Test
    fun sustainedConcurrentSliceChurnReleasesTheChunkExactlyOnce() {
        val cyclesPerWorker = 150_000

        val factory = FreeCountingFactory(BufferFactory.managed())
        val pool =
            BufferPool(
                threadingMode = ThreadingMode.MultiThreaded,
                maxPoolSize = 0,
                defaultBufferSize = 64,
                factory = factory,
            )

        // refCount starts at 1 for this chunk reference; every worker below takes and drops its
        // own slice reference in a tight loop, netting zero if (and only if) addRef/releaseRef are
        // race-free. The chunk's own reference is held until every worker finishes.
        val chunk = pool.acquire(64) as PlatformBuffer
        val tracked = factory.lastAllocated ?: error("factory.allocate() was never called")

        val startBarrier = CyclicBarrier(workerCount)
        val futures =
            (0 until workerCount).map {
                executor.submit {
                    startBarrier.await()
                    repeat(cyclesPerWorker) {
                        val slice = chunk.slice()
                        slice.freeNativeMemory()
                    }
                }
            }
        futures.forEach { it.get(60, TimeUnit.SECONDS) }

        // No release should have happened yet: the chunk's own reference is still outstanding.
        // A nonzero count here means a lost update let some worker's releaseRef() observe the
        // count reaching zero while the chunk's own reference (and possibly other workers') was
        // still live -- a premature, spurious release.
        val duringChurnFreeCount = tracked.freeCount.get()

        // Drop the chunk's own reference now that every worker has finished.
        chunk.freeNativeMemory()
        val finalFreeCount = tracked.freeCount.get()

        assertEquals(
            0,
            duringChurnFreeCount,
            "the pool believed this chunk's last reference was dropped $duringChurnFreeCount " +
                "time(s) DURING the $workerCount-thread addRef/releaseRef churn, while the " +
                "owner's own reference was still outstanding -- a lost update let a releaseRef() " +
                "observe a premature zero",
        )
        assertEquals(
            1,
            finalFreeCount,
            "the chunk must be returned to the pool exactly once, when the owner's own " +
                "reference is dropped; observed $finalFreeCount release(s) in total after " +
                "$workerCount threads x $cyclesPerWorker addRef/releaseRef cycles",
        )
    }

    /**
     * Wraps a delegate buffer so [freeNativeMemory] calls are countable without ever touching real
     * native memory. See the class doc for why a genuine double free is unsafe in this test.
     */
    private class FreeCountingBuffer(
        private val inner: PlatformBuffer,
    ) : PlatformBuffer by inner {
        val freeCount = AtomicInteger(0)

        override fun freeNativeMemory() {
            freeCount.incrementAndGet()
            inner.freeNativeMemory()
        }
    }

    /** Wraps every buffer [delegate] allocates in a [FreeCountingBuffer] and remembers the latest one. */
    private class FreeCountingFactory(
        private val delegate: BufferFactory,
    ) : BufferFactory {
        @Volatile
        var lastAllocated: FreeCountingBuffer? = null
            private set

        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer {
            val wrapped = FreeCountingBuffer(delegate.allocate(size, byteOrder))
            lastAllocated = wrapped
            return wrapped
        }

        override fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder,
        ): PlatformBuffer = delegate.wrap(array, byteOrder)
    }
}
