package com.ditchoom.buffer.pool

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadWriteBuffer

/**
 * A buffer pool with separate small and large tiers to minimize memory waste
 * for bimodal allocation patterns (e.g., MQTT: tiny control packets + large payloads).
 *
 * Without tiering, a single pool with `defaultBufferSize = 64KB` wastes 16,000x memory
 * for a 4-byte PUBACK. With tiering:
 * - PUBACK (4 bytes) → small pool → 512-byte buffer (128x waste, not 16,000x)
 * - PUBLISH (4KB) → large pool → 8KB buffer (2x waste, not 16x)
 *
 * Both tiers maintain independent free lists, giving near-100% hit rates for both
 * control and data traffic patterns.
 *
 * ## Usage
 * ```kotlin
 * val pool = TieredBufferPool(
 *     smallThreshold = 512,
 *     smallDefaultSize = 512,
 *     largeDefaultSize = 8192,
 * )
 * pool.withBuffer(4) { buffer ->
 *     // Gets a 512-byte buffer from the small pool
 *     buffer.writeInt(42)
 * }
 * pool.withBuffer(4096) { buffer ->
 *     // Gets an 8192-byte buffer from the large pool
 *     buffer.writeBytes(payload)
 * }
 * ```
 *
 * @param smallThreshold Requests at or below this size go to the small pool
 * @param smallDefaultSize Default buffer size for the small pool
 * @param largeDefaultSize Default buffer size for the large pool
 * @param factory Buffer factory for allocating new buffers
 * @param threadingMode Threading model for both internal pools
 * @param maxPoolSize Maximum buffers to keep in each pool (not combined)
 */
class TieredBufferPool(
    val smallThreshold: Int = 512,
    val smallDefaultSize: Int = 512,
    val largeDefaultSize: Int = DEFAULT_NETWORK_BUFFER_SIZE,
    val factory: BufferFactory = BufferFactory.Default,
    threadingMode: ThreadingMode = ThreadingMode.MultiThreaded,
    maxPoolSize: Int = 64,
) : BufferPool {
    val smallPool: BufferPool = BufferPool(threadingMode, maxPoolSize, smallDefaultSize, factory)
    val largePool: BufferPool = BufferPool(threadingMode, maxPoolSize, largeDefaultSize, factory)

    override fun acquire(minSize: Int): ReadWriteBuffer =
        if (minSize <= smallThreshold) {
            smallPool.acquire(minSize)
        } else {
            largePool.acquire(minSize)
        }

    override fun release(buffer: ReadWriteBuffer) {
        // PooledBuffer tracks which pool it came from — delegate to that pool
        when (buffer) {
            is PooledBuffer -> buffer.pool.release(buffer)
            else -> {
                if (buffer.capacity <= smallThreshold) {
                    smallPool.release(buffer)
                } else {
                    largePool.release(buffer)
                }
            }
        }
    }

    /**
     * Returns aggregated statistics from both pools.
     */
    override fun stats(): PoolStats {
        val small = smallPool.stats()
        val large = largePool.stats()
        return PoolStats(
            totalAllocations = small.totalAllocations + large.totalAllocations,
            poolHits = small.poolHits + large.poolHits,
            poolMisses = small.poolMisses + large.poolMisses,
            currentPoolSize = small.currentPoolSize + large.currentPoolSize,
            peakPoolSize = small.peakPoolSize + large.peakPoolSize,
        )
    }

    /**
     * Returns statistics for the small pool only.
     */
    fun smallPoolStats(): PoolStats = smallPool.stats()

    /**
     * Returns statistics for the large pool only.
     */
    fun largePoolStats(): PoolStats = largePool.stats()

    override fun clear() {
        smallPool.clear()
        largePool.clear()
    }
}
