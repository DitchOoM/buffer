package com.ditchoom.buffer

/**
 * Creates a larger buffer, copies this buffer's written content, and returns the new buffer.
 *
 * The new buffer preserves the current position so writing can continue seamlessly.
 * The old buffer (this) is freed via [PlatformBuffer.freeNativeMemory]:
 * - If this was a [pool-acquired buffer][com.ditchoom.buffer.pool.PooledBuffer], it is returned to its pool.
 * - If this was a [deterministic buffer][CloseableBuffer], its native memory is freed.
 * - Otherwise, it becomes eligible for GC.
 *
 * **Do not use the old buffer reference after calling this function.**
 *
 * ## Pool Integration
 *
 * Works naturally with pooled factories — no special pool logic needed:
 * ```kotlin
 * val pool = BufferPool(factory = BufferFactory.Default)
 * val factory = BufferFactory.Default.withPooling(pool)
 * var buffer = factory.allocate(64)
 * buffer.writeString("data...")
 * if (buffer.remaining() < 100) {
 *     buffer = buffer.growBuffer(factory) // old returned to pool, new acquired from pool
 * }
 * ```
 *
 * @param factory The factory to use for allocating the new buffer. Should match the factory
 *   that created this buffer for consistent memory management.
 * @param minCapacity The minimum capacity of the new buffer. Must be greater than current
 *   [capacity][PlatformBuffer.capacity]. Defaults to 2x current capacity (minimum 16).
 * @return A new [PlatformBuffer] with the content copied and position preserved.
 * @throws IllegalArgumentException if [minCapacity] is not greater than current capacity.
 */
fun PlatformBuffer.growBuffer(
    factory: BufferFactory,
    minCapacity: Int = maxOf(capacity * 2, 16),
): PlatformBuffer {
    require(minCapacity > capacity) {
        "minCapacity ($minCapacity) must be greater than current capacity ($capacity)"
    }
    val savedPosition = position()
    resetForRead() // position=0, limit=savedPosition
    val newBuffer = factory.allocate(minCapacity, byteOrder)
    newBuffer.write(this) // efficient platform copy; newBuffer.position == savedPosition
    freeNativeMemory() // returns to pool if PooledBuffer, frees if deterministic
    return newBuffer
}
