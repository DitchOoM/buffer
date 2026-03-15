package com.ditchoom.buffer.pool

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadWriteBuffer

/**
 * Fast buffer pool implementation optimized for single-threaded access.
 *
 * NOT thread-safe. Use when pool is confined to a single coroutine/thread.
 * For multi-threaded access, use [LockFreeBufferPool] instead.
 */
internal class SingleThreadedBufferPool(
    private val maxPoolSize: Int,
    private val defaultBufferSize: Int,
    private val factory: BufferFactory,
) : BufferPool {
    private val pool = ArrayDeque<PlatformBuffer>(maxPoolSize)

    private var totalAllocations = 0L
    private var poolHits = 0L
    private var poolMisses = 0L
    private var peakPoolSize = 0

    override fun acquire(minSize: Int): ReadWriteBuffer {
        totalAllocations++
        val size = maxOf(minSize, defaultBufferSize)

        val buffer = pool.removeLastOrNull()

        val raw =
            if (buffer != null && buffer.capacity >= size) {
                poolHits++
                buffer.resetForWrite()
                buffer
            } else {
                poolMisses++
                factory.allocate(size)
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

        if (pool.size < maxPoolSize) {
            pool.addLast(raw)
            if (pool.size > peakPoolSize) {
                peakPoolSize = pool.size
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
            currentPoolSize = pool.size,
            peakPoolSize = peakPoolSize,
        )

    override fun clear() {
        // Drain-and-free: remove each buffer before freeing it.
        // Using an iterator (for-in) is unsafe because freeNativeMemory() could
        // re-enter release() (via PooledBuffer.releaseRef) and modify the ArrayDeque
        // during iteration, corrupting its internal head/tail indices (size becomes -1).
        // This pattern matches LockFreeBufferPool.clear().
        while (pool.isNotEmpty()) {
            pool.removeFirst().freeNativeMemory()
        }
    }
}
