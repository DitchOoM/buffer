package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.withPooling

/**
 * Borrows a buffer from this pool, hands the block a [secure][toSecureBuffer] view of it, and
 * guarantees the backing is wiped before the buffer returns to the pool.
 *
 * This is the instance-level "borrow and upgrade" path: the buffer is acquired from the pool,
 * zero-initialized (so no secret a previous borrower left behind leaks in), used inside [block],
 * then wiped and released back to the pool. Because the wipe runs in the `finally`, it happens on
 * every exit including exceptions — unlike a bare [toSecureBuffer], which only wipes if the caller
 * remembers to free.
 *
 * Prefer this (or [secureFixedPool]) over pooling key material by hand: a general pool reuses
 * buffers, so a secret written into a pooled buffer would otherwise linger in the pool's free list
 * for the next, possibly unrelated, borrower to read.
 *
 * ```kotlin
 * pool.withSecureBuffer(32) { key ->
 *     deriveKeyInto(key)
 *     encrypt(message, key)
 * } // key zeroed, buffer returned to pool
 * ```
 *
 * **Wipe cost is O(capacity), not O(minSize).** A shared pool hands out its largest cached buffer,
 * so `withSecureBuffer(32)` on a 64 KiB pool wipes 64 KiB. For hot, fixed-size key material use a
 * dedicated [secureFixedPool] where capacity == size, so the wipe is O(size) and reuse is perfect.
 *
 * @param minSize minimum capacity to borrow
 * @param block runs against the secure view; do not let the buffer escape the block
 */
inline fun <T> BufferPool.withSecureBuffer(
    minSize: Int = 0,
    block: (PlatformBuffer) -> T,
): T {
    val pooled = acquire(minSize) as PlatformBuffer
    val secure = pooled.toSecureBuffer(zeroFirst = true)
    return try {
        block(secure)
    } finally {
        // SecureBuffer.freeNativeMemory wipes the full backing, then the underlying PooledBuffer
        // returns the (now-zeroed) raw buffer to the pool via its reference count.
        secure.freeNativeMemory()
    }
}

/**
 * A [BufferFactory] for hot, fixed-size cryptographic buffers: every allocation borrows a buffer
 * from a dedicated pool and wraps it secure, so freeing it wipes the backing and returns it to the
 * pool for reuse — no native malloc/free per operation, and the wipe stays O([bufferSize]).
 *
 * Because the pool is homogeneous (every buffer is [bufferSize]) reuse is perfect, and since the
 * whole buffer is the secret the whole-buffer wipe has no "did I bound it right" risk. This is the
 * recommended shape for repeatedly deriving/holding same-size key material:
 *
 * ```kotlin
 * val keyPool = secureFixedPool(bufferSize = 32)
 * keyPool.allocate(32).use { key ->     // borrowed + zeroed
 *     deriveKeyInto(key)
 *     encrypt(message, key)
 * }                                      // wiped, returned to the pool
 * ```
 *
 * The secure layer wraps the pool-borrowed buffer (via [BufferFactory.decorate]), so the buffer
 * cached in the pool is the plain backing — no [SecureBuffer] wrapper lingers between borrows.
 *
 * @param bufferSize the fixed capacity, in bytes, of every buffer in the pool
 * @param maxPoolSize maximum number of buffers the pool retains
 * @param threadingMode [ThreadingMode.SingleThreaded] (default, fastest) or
 *   [ThreadingMode.MultiThreaded] for concurrent access
 * @param base the underlying allocation strategy for the pool's buffers; defaults to
 *   [deterministic][BufferFactory.Companion.deterministic] so the wipe is guaranteed to run
 */
fun secureFixedPool(
    bufferSize: Int,
    maxPoolSize: Int = 64,
    threadingMode: ThreadingMode = ThreadingMode.SingleThreaded,
    base: BufferFactory = BufferFactory.deterministic(),
): BufferFactory {
    require(bufferSize >= 1) { "bufferSize must be >= 1, was $bufferSize" }
    val pool = BufferPool(threadingMode, maxPoolSize, defaultBufferSize = bufferSize, factory = base)
    // secure() is the delegate; withPooling borrows from `pool` and applies secure().decorate()
    // to the borrowed buffer → SecureBuffer(zeroInit(pooled)), wipe-then-return-to-pool on free.
    return base.secure(maxAllocationBytes = bufferSize).withPooling(pool)
}
