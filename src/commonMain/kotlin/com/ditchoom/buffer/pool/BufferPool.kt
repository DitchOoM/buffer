package com.ditchoom.buffer.pool

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * High-performance buffer pool that minimizes allocations by reusing buffers.
 *
 * Two allocation strategies are available:
 * - [SafeBufferPool]: Uses PlatformBuffer with GC-managed memory
 * - [UnsafeBufferPool]: Uses UnsafeBuffer with manual memory management for maximum performance
 */
interface BufferPool {
    /**
     * Acquires a buffer of at least the specified size.
     * The buffer may be larger than requested.
     */
    fun acquire(minSize: Int): PooledBuffer

    /**
     * Releases a buffer back to the pool for reuse.
     */
    fun release(buffer: PooledBuffer)

    /**
     * Returns statistics about pool usage.
     */
    fun stats(): PoolStats

    /**
     * Clears all pooled buffers, freeing memory.
     */
    fun clear()
}

/**
 * A buffer that has been acquired from a pool.
 * Must be released back to the pool when done.
 */
interface PooledBuffer :
    ReadBuffer,
    WriteBuffer {
    val capacity: Int
    override val byteOrder: ByteOrder

    /**
     * Returns this buffer to its pool.
     * After calling this, the buffer should not be used.
     */
    fun release()
}

/**
 * Statistics about buffer pool usage.
 */
data class PoolStats(
    val totalAllocations: Long,
    val poolHits: Long,
    val poolMisses: Long,
    val currentPoolSize: Int,
    val peakPoolSize: Int,
)

/**
 * Creates a safe buffer pool using PlatformBuffer.
 */
fun createSafeBufferPool(
    initialPoolSize: Int = 16,
    maxPoolSize: Int = 64,
    defaultBufferSize: Int = 8192,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): BufferPool = SafeBufferPoolImpl(initialPoolSize, maxPoolSize, defaultBufferSize, byteOrder)

/**
 * Creates an unsafe buffer pool using UnsafeBuffer for maximum performance.
 * Buffers MUST be released back to the pool to avoid memory leaks.
 */
fun createUnsafeBufferPool(
    initialPoolSize: Int = 16,
    maxPoolSize: Int = 64,
    defaultBufferSize: Int = 8192,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): BufferPool = UnsafeBufferPoolImpl(initialPoolSize, maxPoolSize, defaultBufferSize, byteOrder)
