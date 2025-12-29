package com.ditchoom.buffer.pool

import com.ditchoom.buffer.ByteOrder

/**
 * High-performance buffer pool that minimizes allocations by reusing buffers.
 *
 * Use [BufferPool.Companion.invoke] to create an instance with the desired threading model.
 */
sealed interface BufferPool {
    /**
     * Acquires a buffer of at least the specified size.
     * The buffer may be larger than requested.
     */
    fun acquire(minSize: Int = 0): PooledBuffer

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

    companion object {
        /**
         * Creates a buffer pool with the specified threading model.
         *
         * @param threadingMode Single-threaded (faster) or multi-threaded (thread-safe)
         * @param maxPoolSize Maximum buffers to keep in pool
         * @param defaultBufferSize Default size for acquired buffers
         * @param byteOrder Byte order for buffers
         */
        operator fun invoke(
            threadingMode: ThreadingMode = ThreadingMode.SingleThreaded,
            maxPoolSize: Int = 64,
            defaultBufferSize: Int = DEFAULT_FILE_BUFFER_SIZE,
            byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
        ): BufferPool =
            when (threadingMode) {
                ThreadingMode.SingleThreaded -> SingleThreadedBufferPool(maxPoolSize, defaultBufferSize, byteOrder)
                ThreadingMode.MultiThreaded -> LockFreeBufferPool(maxPoolSize, defaultBufferSize, byteOrder)
            }

        /**
         * Creates a buffer pool with default single-threaded mode.
         *
         * @param maxPoolSize Maximum buffers to keep in pool
         * @param defaultBufferSize Default size for acquired buffers
         * @param byteOrder Byte order for buffers
         */
        operator fun invoke(
            maxPoolSize: Int = 64,
            defaultBufferSize: Int = DEFAULT_FILE_BUFFER_SIZE,
            byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
        ): BufferPool = SingleThreadedBufferPool(maxPoolSize, defaultBufferSize, byteOrder)
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
 * A buffer that has been acquired from a pool.
 * Must be released back to the pool when done.
 */
interface PooledBuffer :
    com.ditchoom.buffer.ReadBuffer,
    com.ditchoom.buffer.WriteBuffer {
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
): BufferPool = BufferPool(threadingMode, maxPoolSize, defaultBufferSize, byteOrder)

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
    block: (PooledBuffer) -> T,
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
    block: (BufferPool) -> T,
): T {
    val pool = BufferPool(threadingMode, maxPoolSize, defaultBufferSize, byteOrder)
    try {
        return block(pool)
    } finally {
        pool.clear()
    }
}
