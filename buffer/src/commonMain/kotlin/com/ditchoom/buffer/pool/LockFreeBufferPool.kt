package com.ditchoom.buffer.pool

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadWriteBuffer
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Thread-safe buffer pool using lock-free algorithms (Treiber stack).
 *
 * Uses Compare-And-Swap (CAS) operations for thread-safe access without locks.
 * Suitable for concurrent access from multiple threads/coroutines.
 *
 * Allocations are rounded up to size classes (power-of-two up to 1 MiB, 1 MiB
 * multiples above) and cached buffers are
 * bucketed by class — one Treiber stack per class ([BufferSizeClass]) — so mixed-size
 * churn neither allocates unique odd sizes (which fragment freelist allocators,
 * catastrophically on Android/ART — see ANDROID_ART_ALLOCATOR.md) nor
 * frees-and-reallocates on wrong-sized pops.
 *
 * For single-threaded use, prefer [SingleThreadedBufferPool] for better performance.
 *
 * Uses `kotlin.concurrent.atomics` (stdlib) rather than the atomicfu plugin so the
 * bytecode is portable to Android without a build-time transform step — the atomicfu
 * transform didn't run for the `-android` variant, leaving `kotlinx.atomicfu.AtomicFU`
 * referenced at runtime with no declared dependency (NoClassDefFoundError). Mirrors
 * [com.ditchoom.buffer.codec.GrowableWriteBufferPool].
 *
 * `@Suppress("TooManyFunctions")`: the function count exceeds detekt's default cap because
 * size-class bucketing splits the Treiber-stack helpers (push/pop/popAtLeast/popAny) out
 * from the BufferPool interface methods; each is a small, single-purpose lock-free primitive.
 */
@Suppress("TooManyFunctions")
@OptIn(ExperimentalAtomicApi::class)
internal class LockFreeBufferPool(
    private val maxPoolSize: Int,
    private val defaultBufferSize: Int,
    private val factory: BufferFactory,
) : BufferPool {
    // Treiber stack node
    private class Node(
        val buffer: PlatformBuffer,
        val next: Node?,
    )

    // One lock-free stack per size class; a buffer lives in the bucket of its
    // capacity's floor log2, so every buffer in bucket k has capacity >= 1 shl k.
    private val heads = Array(BufferSizeClass.BUCKET_COUNT) { AtomicReference<Node?>(null) }

    // Atomic size counter across all buckets, to avoid traversing the lists
    private val poolSize = AtomicInt(0)

    // Atomic statistics
    private val totalAllocations = AtomicLong(0L)
    private val poolHits = AtomicLong(0L)
    private val poolMisses = AtomicLong(0L)
    private val peakPoolSize = AtomicInt(0)

    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer {
        val buffer = acquire(size)
        if (buffer is PlatformBuffer && buffer.byteOrder == byteOrder) return buffer
        release(buffer)
        return allocateOrReclaim {
            factory.allocate(BufferSizeClass.roundUp(maxOf(size, defaultBufferSize)), byteOrder)
        }
    }

    override fun wrap(
        array: ByteArray,
        byteOrder: ByteOrder,
    ): PlatformBuffer = factory.wrap(array, byteOrder)

    override fun acquire(minSize: Int): ReadWriteBuffer {
        totalAllocations.update { it + 1 }
        val size = maxOf(minSize, defaultBufferSize)

        // Try to pop a fitting buffer from the size-class stacks (lock-free)
        val buffer = popAtLeast(size)

        val raw =
            if (buffer != null && buffer.capacity >= size) {
                poolHits.update { it + 1 }
                buffer.resetForWrite()
                buffer
            } else {
                poolMisses.update { it + 1 }
                // Only reachable via bucket 0's zero-capacity edge case; free the popped
                // buffer's native memory before allocating fresh, otherwise it leaks
                // (Arena.ofShared never closes, FfmAutoBuffer waits on GC).
                buffer?.freeNativeMemory()
                allocateOrReclaim { factory.allocate(BufferSizeClass.roundUp(size)) }
            }
        return PooledBuffer(raw, this)
    }

    override fun release(buffer: ReadWriteBuffer) {
        val raw =
            when (buffer) {
                is PooledBuffer -> {
                    require(buffer.pool === this) {
                        "Cannot release a buffer to a different pool than the one it was acquired from"
                    }
                    buffer.inner
                }
                is PlatformBuffer -> buffer
                else -> return
            }

        // Only push if under max size (check first to avoid unnecessary work)
        if (poolSize.load() < maxPoolSize) {
            if (push(raw)) {
                // Update peak if needed
                val currentSize = poolSize.load()
                updatePeak(currentSize)
            } else {
                // CAS push failed because pool became full - free the buffer
                raw.freeNativeMemory()
            }
        } else {
            raw.freeNativeMemory()
        }
    }

    override fun stats(): PoolStats =
        PoolStats(
            totalAllocations = totalAllocations.load(),
            poolHits = poolHits.load(),
            poolMisses = poolMisses.load(),
            currentPoolSize = poolSize.load(),
            peakPoolSize = peakPoolSize.load(),
        )

    override fun clear() {
        // Pop all elements and free their native memory, restarting the bucket scan
        // every iteration: freeNativeMemory() could re-enter release() and push onto
        // any bucket, including one that was already drained.
        while (true) {
            val buffer = popAny() ?: break
            buffer.freeNativeMemory()
        }
    }

    /**
     * Lock-free push onto the Treiber stack for the buffer's size class.
     * Returns true if pushed, false if pool is full.
     */
    private fun push(buffer: PlatformBuffer): Boolean {
        // Reserve a slot with a bounded CAS-increment BEFORE touching the bucket head.
        // With one head per size class, pushes to different buckets never contend on
        // the head CAS, so a plain check-then-push would never re-run its stale size
        // check and could admit up to (concurrent releasers - 1) buffers past
        // maxPoolSize. Reserving on the shared counter makes the cap exact; the
        // counter may transiently exceed the number of linked nodes while a push is
        // in flight, which only makes concurrent pops/pushes conservatively strict.
        while (true) {
            val currentSize = poolSize.load()
            if (currentSize >= maxPoolSize) {
                return false
            }
            if (poolSize.compareAndSet(currentSize, currentSize + 1)) break
        }

        // Slot reserved — the Treiber push always succeeds eventually.
        val head = heads[BufferSizeClass.bucketForCapacity(buffer.capacity)]
        while (true) {
            val oldHead = head.load()
            val newNode = Node(buffer, oldHead)

            // CAS: if head hasn't changed, update it
            if (head.compareAndSet(oldHead, newNode)) {
                return true
            }
            // CAS failed, retry
        }
    }

    /**
     * Pops a cached buffer guaranteed to hold [size] bytes, searching the request's own
     * size class first and falling back to larger classes. Returns null on miss.
     */
    private fun popAtLeast(size: Int): PlatformBuffer? {
        for (bucket in BufferSizeClass.bucketForRequest(size) until BufferSizeClass.BUCKET_COUNT) {
            val buffer = pop(heads[bucket])
            if (buffer != null) return buffer
        }
        return null
    }

    /** Pops from any non-empty bucket, smallest class first. Used by [clear]. */
    private fun popAny(): PlatformBuffer? {
        for (head in heads) {
            val buffer = pop(head)
            if (buffer != null) return buffer
        }
        return null
    }

    /**
     * Lock-free pop from one size class's Treiber stack.
     * Returns the buffer or null if that stack is empty.
     */
    private fun pop(head: AtomicReference<Node?>): PlatformBuffer? {
        while (true) {
            val oldHead = head.load() ?: return null
            val newHead = oldHead.next

            // CAS: if head hasn't changed, update it
            if (head.compareAndSet(oldHead, newHead)) {
                poolSize.update { it - 1 }
                return oldHead.buffer
            }
            // CAS failed, retry
        }
    }

    /**
     * Atomically update peak pool size if current is larger.
     */
    private fun updatePeak(currentSize: Int) {
        while (true) {
            val peak = peakPoolSize.load()
            if (currentSize <= peak) return
            if (peakPoolSize.compareAndSet(peak, currentSize)) return
        }
    }

    private inline fun AtomicInt.update(transform: (Int) -> Int) {
        while (true) {
            val current = load()
            if (compareAndSet(current, transform(current))) return
        }
    }

    private inline fun AtomicLong.update(transform: (Long) -> Long) {
        while (true) {
            val current = load()
            if (compareAndSet(current, transform(current))) return
        }
    }
}
