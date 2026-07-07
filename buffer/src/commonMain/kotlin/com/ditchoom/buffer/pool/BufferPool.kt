package com.ditchoom.buffer.pool

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
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
 * val pool = BufferPool(factory = BufferFactory.Default)
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
 * ### Manual acquire/freeNativeMemory
 * ```kotlin
 * val pool = BufferPool(factory = BufferFactory.Default)
 * val buffer = pool.acquire(1024) as PlatformBuffer
 * try {
 *     buffer.writeInt(42)
 * } finally {
 *     buffer.freeNativeMemory() // returns to pool
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
sealed interface BufferPool : BufferFactory {
    /**
     * Acquires a buffer of at least the specified size.
     * The returned buffer is a [PooledBuffer] wrapper whose [freeNativeMemory][PlatformBuffer.freeNativeMemory]
     * returns the buffer to this pool instead of freeing it.
     * The buffer may be larger than requested.
     */
    fun acquire(minSize: Int = 0): ReadWriteBuffer

    /**
     * Allocates a buffer with the requested byte order via the pool when possible,
     * otherwise via the pool's seed factory.
     *
     * When a cached buffer matches [byteOrder] this avoids allocation; on byte-order
     * mismatch or non-[PlatformBuffer] cache content, the cached entry is returned to
     * the pool and a fresh buffer is allocated from the seed factory.
     */
    override fun allocate(
        size: Int,
        byteOrder: ByteOrder,
    ): PlatformBuffer

    /**
     * Wraps an existing byte array. Pools do not cache wrapped arrays — this delegates
     * to the seed factory used to construct the pool.
     */
    override fun wrap(
        array: ByteArray,
        byteOrder: ByteOrder,
    ): PlatformBuffer

    /**
     * Releases a buffer back to the pool for reuse.
     *
     * Prefer [withBuffer] (auto-release) or [PlatformBuffer.freeNativeMemory]
     * on pool-acquired buffers instead of calling this directly.
     *
     * @throws IllegalArgumentException if [buffer] is a [PooledBuffer] from a different pool
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

    /**
     * Whether this pool is safe for concurrent access from multiple threads/coroutines.
     *
     * A pool that is shared across coroutines — e.g. one passed to a socket/websocket, where a buffer
     * is acquired on the read coroutine and freed on the consumer/send coroutine — MUST be
     * [ThreadingMode.MultiThreaded]. Consumers that hand a pre-built pool to such a library should
     * check or construct it accordingly; the library may `require` it.
     */
    val threadingMode: ThreadingMode

    companion object {
        /**
         * Creates a buffer pool with the specified threading model and buffer factory.
         *
         * @param threadingMode Single-threaded (faster) or multi-threaded (thread-safe)
         * @param maxPoolSize Maximum buffers to keep in pool
         * @param defaultBufferSize Default size for acquired buffers
         * @param factory Buffer factory for allocating new buffers
         */
        operator fun invoke(
            threadingMode: ThreadingMode = ThreadingMode.SingleThreaded,
            maxPoolSize: Int = 64,
            defaultBufferSize: Int = DEFAULT_FILE_BUFFER_SIZE,
            factory: BufferFactory = BufferFactory.Default,
        ): BufferPool =
            // Never nest a pool inside a pool. `BufferPool : BufferFactory`, so a pool can be passed
            // as `factory`; wrapping it would produce PooledBuffer(inner = PooledBuffer(pool = inner),
            // pool = outer), and a normal free then runs `outer.release(inner)` where inner belongs to
            // the inner pool — tripping the cross-pool `require` in release() (websocket #19 / 6.8.1
            // Android crash: socket ReadBufferSource does BufferPool(factory = consumer's shared pool)).
            // Reuse the existing pool instead — double-pooling is pure overhead with broken accounting.
            (factory as? BufferPool)
                ?: when (threadingMode) {
                    ThreadingMode.SingleThreaded ->
                        SingleThreadedBufferPool(maxPoolSize, defaultBufferSize, factory)
                    ThreadingMode.MultiThreaded ->
                        LockFreeBufferPool(maxPoolSize, defaultBufferSize, factory)
                }

        /**
         * Creates a buffer pool with default single-threaded mode.
         *
         * @param maxPoolSize Maximum buffers to keep in pool
         * @param defaultBufferSize Default size for acquired buffers
         * @param factory Buffer factory for allocating new buffers
         */
        operator fun invoke(
            maxPoolSize: Int = 64,
            defaultBufferSize: Int = DEFAULT_FILE_BUFFER_SIZE,
            factory: BufferFactory = BufferFactory.Default,
        ): BufferPool =
            // See the threading-mode overload above: never nest a pool inside a pool.
            (factory as? BufferPool) ?: SingleThreadedBufferPool(maxPoolSize, defaultBufferSize, factory)
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
    factory: BufferFactory = BufferFactory.Default,
): BufferPool = BufferPool(threadingMode, maxPoolSize, defaultBufferSize, factory)

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
    factory: BufferFactory = BufferFactory.Default,
    block: (BufferPool) -> T,
): T {
    val pool = BufferPool(threadingMode, maxPoolSize, defaultBufferSize, factory)
    try {
        return block(pool)
    } finally {
        pool.clear()
    }
}
