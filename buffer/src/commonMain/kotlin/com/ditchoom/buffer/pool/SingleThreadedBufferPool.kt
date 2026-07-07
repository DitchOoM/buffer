package com.ditchoom.buffer.pool

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadWriteBuffer

/**
 * Fast buffer pool implementation optimized for single-threaded access.
 *
 * NOT thread-safe. Use when pool is confined to a single coroutine/thread.
 * For multi-threaded access, use [LockFreeBufferPool] instead.
 *
 * Allocations are rounded up to size classes (power-of-two up to 1 MiB, 1 MiB
 * multiples above) and cached buffers are
 * bucketed by class ([BufferSizeClass]), so mixed-size churn neither allocates unique
 * odd sizes (which fragment freelist allocators, catastrophically on Android/ART — see
 * ANDROID_ART_ALLOCATOR.md) nor frees-and-reallocates on wrong-sized pops.
 */
internal class SingleThreadedBufferPool(
    private val maxPoolSize: Int,
    private val defaultBufferSize: Int,
    private val factory: BufferFactory,
) : BufferPool {
    override val threadingMode: ThreadingMode get() = ThreadingMode.SingleThreaded

    // One deque per size class; a buffer lives in the bucket of its capacity's floor
    // log2, so every buffer in bucket k has capacity >= 1 shl k.
    private val buckets = Array(BufferSizeClass.BUCKET_COUNT) { ArrayDeque<PlatformBuffer>() }
    private var pooledCount = 0

    private var totalAllocations = 0L
    private var poolHits = 0L
    private var poolMisses = 0L
    private var peakPoolSize = 0

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
        totalAllocations++
        val size = maxOf(minSize, defaultBufferSize)

        val buffer = popAtLeast(size)

        val raw =
            if (buffer != null && buffer.capacity >= size) {
                poolHits++
                buffer.resetForWrite()
                buffer
            } else {
                poolMisses++
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
                is PooledBuffer ->
                    when {
                        buffer.pool === this -> buffer.inner
                        // Defensive backstop for nested pools (BufferPool.invoke collapses these, so
                        // this only fires if one is built directly via the internal constructor): a
                        // buffer whose owner is THIS pool's own backing factory legitimately belongs to
                        // that inner pool — route it back there instead of crashing. Genuine misuse
                        // (an unrelated pool) still throws below.
                        buffer.pool === factory -> {
                            buffer.freeNativeMemory()
                            return
                        }
                        else ->
                            throw IllegalArgumentException(
                                "Cannot release a buffer to a different pool than the one it was acquired from",
                            )
                    }
                is PlatformBuffer -> buffer
                else -> return
            }

        if (pooledCount < maxPoolSize) {
            buckets[BufferSizeClass.bucketForCapacity(raw.capacity)].addLast(raw)
            pooledCount++
            if (pooledCount > peakPoolSize) {
                peakPoolSize = pooledCount
            }
        } else {
            raw.freeNativeMemory()
        }
    }

    override fun stats(): PoolStats =
        PoolStats(
            totalAllocations = totalAllocations,
            poolHits = poolHits,
            poolMisses = poolMisses,
            currentPoolSize = pooledCount,
            peakPoolSize = peakPoolSize,
        )

    override fun clear() {
        // Drain-and-free: remove each buffer before freeing it, restarting the bucket
        // scan every iteration. freeNativeMemory() could re-enter release() (via
        // PooledBuffer.releaseRef) and modify any deque, so neither an iterator (for-in)
        // nor a cached per-bucket loop is safe. This pattern matches LockFreeBufferPool.
        while (true) {
            val deque = buckets.firstOrNull { it.isNotEmpty() } ?: break
            pooledCount--
            deque.removeFirst().freeNativeMemory()
        }
    }

    /**
     * Removes and returns a cached buffer guaranteed to hold [size] bytes, searching the
     * request's own size class first and falling back to larger classes, or null.
     */
    private fun popAtLeast(size: Int): PlatformBuffer? {
        for (bucket in BufferSizeClass.bucketForRequest(size) until BufferSizeClass.BUCKET_COUNT) {
            val buffer = buckets[bucket].removeLastOrNull()
            if (buffer != null) {
                pooledCount--
                return buffer
            }
        }
        return null
    }
}
