package com.ditchoom.buffer.pool

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

/**
 * A buffer wrapper that returns its inner buffer to a pool when all references are released.
 *
 * Created by [BufferPool.acquire] to make pool-acquired buffers transparent.
 * All buffer operations delegate to the inner [PlatformBuffer].
 *
 * Uses reference counting to track outstanding slices. The inner buffer is returned
 * to the pool only when the chunk itself AND all slices created from it are released.
 * This prevents the pool from reusing memory that is still referenced by slices.
 */
internal class PooledBuffer(
    internal val inner: PlatformBuffer,
    private val pool: BufferPool,
) : PlatformBuffer by inner {
    private var freed = false
    private var refCount = 1 // 1 for the chunk reference in StreamProcessor

    internal fun addRef() {
        refCount++
    }

    internal fun releaseRef() {
        if (--refCount == 0) {
            pool.release(inner)
        }
    }

    override fun freeNativeMemory() {
        if (!freed) {
            freed = true
            releaseRef()
        }
    }

    override fun slice(): ReadBuffer {
        addRef()
        return TrackedSlice(inner.slice(), this)
    }

    @Suppress("DEPRECATION")
    override fun unwrap(): PlatformBuffer = inner.unwrap()

    override suspend fun close() {
        freeNativeMemory()
    }
}
