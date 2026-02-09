package com.ditchoom.buffer.pool

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadWriteBuffer
import com.ditchoom.buffer.allocate

/**
 * Fast buffer pool implementation optimized for single-threaded access.
 *
 * NOT thread-safe. Use when pool is confined to a single coroutine/thread.
 * For multi-threaded access, use [LockFreeBufferPool] instead.
 */
internal class SingleThreadedBufferPool(
    private val maxPoolSize: Int,
    private val defaultBufferSize: Int,
    private val byteOrder: ByteOrder,
    private val allocationZone: AllocationZone,
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

        return if (buffer != null && buffer.capacity >= size) {
            poolHits++
            buffer.resetForWrite()
            buffer
        } else {
            poolMisses++
            PlatformBuffer.allocate(size, allocationZone, byteOrder)
        }
    }

    override fun release(buffer: ReadWriteBuffer) {
        val platformBuffer = buffer as? PlatformBuffer ?: return

        if (pool.size < maxPoolSize) {
            platformBuffer.resetForWrite()
            pool.addLast(platformBuffer)
            if (pool.size > peakPoolSize) {
                peakPoolSize = pool.size
            }
        } else {
            platformBuffer.freeNativeMemory()
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
        for (buffer in pool) {
            buffer.freeNativeMemory()
        }
        pool.clear()
    }
}
