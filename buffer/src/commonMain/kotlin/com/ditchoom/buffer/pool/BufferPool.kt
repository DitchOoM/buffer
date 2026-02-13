package com.ditchoom.buffer.pool

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.ReadWriteBuffer

/**
 * High-performance buffer pool that minimizes allocations by reusing buffers.
 *
 * Buffer pools significantly reduce garbage collection overhead in high-throughput
 * scenarios like network I/O, file processing, and protocol parsing.
 *
 * ## Usage Patterns
 *
 * ### Recommended: withBuffer (auto-release)
 * ```kotlin
 * val pool = BufferPool()
 * pool.withBuffer(1024) { buffer ->
 *     buffer.writeInt(42)
 *     buffer.writeString("Hello")
 *     buffer.resetForRead()
 *     // Use buffer...
 * } // Buffer automatically released
 * ```
 *
 * ### Scoped pool with withPool
 * ```kotlin
 * withPool(defaultBufferSize = 8192) { pool ->
 *     pool.withBuffer { buffer ->
 *         // Process data
 *     }
 * } // Pool automatically cleared
 * ```
 *
 * ### Manual acquire/release
 * ```kotlin
 * val pool = BufferPool()
 * val buffer = pool.acquire(1024)
 * try {
 *     buffer.writeInt(42)
 * } finally {
 *     pool.release(buffer)
 * }
 * ```
 *
 * ## Threading Models
 *
 * - [ThreadingMode.SingleThreaded]: Fastest, but NOT thread-safe
 * - [ThreadingMode.MultiThreaded]: Lock-free, safe for concurrent access
 *
 * @see withBuffer for auto-releasing buffer usage
 * @see withPool for scoped pool creation
 */
sealed interface BufferPool {
    /**
     * Acquires a buffer of at least the specified size.
     * The returned buffer is a [PooledBuffer] wrapper whose [freeNativeMemory][PlatformBuffer.freeNativeMemory]
     * returns the buffer to this pool instead of freeing it.
     * The buffer may be larger than requested.
     */
    fun acquire(minSize: Int = 0): ReadWriteBuffer

    /**
     * Releases a buffer back to the pool for reuse.
     * The buffer must have been acquired from this pool.
     * Buffers that are not [PlatformBuffer] instances are silently ignored.
     */
    fun release(buffer: ReadWriteBuffer)

    /**
     * Returns statistics about pool usage.
     */
    fun stats(): PoolStats

    /**
     * Clears all pooled buffers, freeing memory.
     */
    fun clear()

    companion object {
        /**
         * Creates a buffer pool with the specified threading model.
         *
         * @param threadingMode Single-threaded (faster) or multi-threaded (thread-safe)
         * @param maxPoolSize Maximum buffers to keep in pool
         * @param defaultBufferSize Default size for acquired buffers
         * @param byteOrder Byte order for buffers
         * @param allocationZone Memory allocation strategy for new buffers
         */
        operator fun invoke(
            threadingMode: ThreadingMode = ThreadingMode.SingleThreaded,
            maxPoolSize: Int = 64,
            defaultBufferSize: Int = DEFAULT_FILE_BUFFER_SIZE,
            byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
            allocationZone: AllocationZone = AllocationZone.Direct,
        ): BufferPool =
            when (threadingMode) {
                ThreadingMode.SingleThreaded ->
                    SingleThreadedBufferPool(maxPoolSize, defaultBufferSize, byteOrder, allocationZone)
                ThreadingMode.MultiThreaded ->
                    LockFreeBufferPool(maxPoolSize, defaultBufferSize, byteOrder, allocationZone)
            }

        /**
         * Creates a buffer pool with default single-threaded mode.
         *
         * @param maxPoolSize Maximum buffers to keep in pool
         * @param defaultBufferSize Default size for acquired buffers
         * @param byteOrder Byte order for buffers
         * @param allocationZone Memory allocation strategy for new buffers
         */
        operator fun invoke(
            maxPoolSize: Int = 64,
            defaultBufferSize: Int = DEFAULT_FILE_BUFFER_SIZE,
            byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
            allocationZone: AllocationZone = AllocationZone.Direct,
        ): BufferPool = SingleThreadedBufferPool(maxPoolSize, defaultBufferSize, byteOrder, allocationZone)
    }
}

/**
 * Threading mode for buffer pool operations.
 */
enum class ThreadingMode {
    /**
     * Optimized for single-threaded access. Faster but NOT thread-safe.
     * Use when pool is confined to a single coroutine/thread.
     */
    SingleThreaded,

    /**
     * Thread-safe using lock-free algorithms. Safe for concurrent access
     * from multiple threads/coroutines.
     */
    MultiThreaded,
}

/**
 * Statistics about buffer pool usage for monitoring and tuning.
 *
 * @property totalAllocations Total number of acquire() calls
 * @property poolHits Number of times a pooled buffer was reused
 * @property poolMisses Number of times a new buffer had to be allocated
 * @property currentPoolSize Number of buffers currently in the pool
 * @property peakPoolSize Maximum number of buffers ever held in the pool
 */
data class PoolStats(
    val totalAllocations: Long,
    val poolHits: Long,
    val poolMisses: Long,
    val currentPoolSize: Int,
    val peakPoolSize: Int,
) {
    /** Percentage of acquire() calls satisfied from the pool (0.0 to 1.0). */
    val hitRate: Double
        get() = if (totalAllocations > 0) poolHits.toDouble() / totalAllocations else 0.0
}

/**
 * Default buffer size for file I/O operations (64 KB).
 */
const val DEFAULT_FILE_BUFFER_SIZE: Int = 64 * 1024

/**
 * Default buffer size for network I/O operations (8 KB).
 */
const val DEFAULT_NETWORK_BUFFER_SIZE: Int = 8 * 1024

/**
 * Creates a buffer pool. Defaults to single-threaded for best performance.
 *
 * @see BufferPool.Companion.invoke for full options
 */
fun createBufferPool(
    threadingMode: ThreadingMode = ThreadingMode.SingleThreaded,
    maxPoolSize: Int = 64,
    defaultBufferSize: Int = DEFAULT_FILE_BUFFER_SIZE,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    allocationZone: AllocationZone = AllocationZone.Direct,
): BufferPool = BufferPool(threadingMode, maxPoolSize, defaultBufferSize, byteOrder, allocationZone)

/**
 * Acquires a buffer, executes the block, and automatically releases the buffer.
 *
 * This is the preferred way to use pooled buffers as it ensures proper cleanup:
 * ```kotlin
 * pool.withBuffer(1024) { buffer ->
 *     buffer.writeInt(42)
 *     buffer.resetForRead()
 *     println(buffer.readInt())
 * }
 * ```
 */
inline fun <T> BufferPool.withBuffer(
    minSize: Int = 0,
    block: (ReadWriteBuffer) -> T,
): T {
    val buffer = acquire(minSize)
    try {
        return block(buffer)
    } finally {
        release(buffer)
    }
}

/**
 * Creates a buffer pool, executes the block, and automatically clears the pool.
 *
 * Convenient for scoped pool usage:
 * ```kotlin
 * withPool { pool ->
 *     pool.withBuffer(1024) { buffer ->
 *         buffer.writeInt(42)
 *     }
 * } // pool is cleared automatically
 * ```
 */
inline fun <T> withPool(
    threadingMode: ThreadingMode = ThreadingMode.SingleThreaded,
    maxPoolSize: Int = 64,
    defaultBufferSize: Int = DEFAULT_FILE_BUFFER_SIZE,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    allocationZone: AllocationZone = AllocationZone.Direct,
    block: (BufferPool) -> T,
): T {
    val pool = BufferPool(threadingMode, maxPoolSize, defaultBufferSize, byteOrder, allocationZone)
    try {
        return block(pool)
    } finally {
        pool.clear()
    }
}
