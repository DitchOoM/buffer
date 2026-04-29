package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadWriteBuffer
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool

/**
 * Strategy for allocating buffers during compression/decompression.
 * Allows control over memory allocation to optimize for different use cases.
 */
sealed interface BufferAllocator {
    /**
     * Allocates a buffer of the specified size.
     */
    fun allocate(size: Int): ReadWriteBuffer

    /**
     * Allocate buffers using direct memory (default).
     */
    data object Direct : BufferAllocator {
        override fun allocate(size: Int): ReadWriteBuffer = BufferFactory.Default.allocate(size)
    }

    /**
     * Allocate buffers using heap memory.
     */
    data object Heap : BufferAllocator {
        override fun allocate(size: Int): ReadWriteBuffer = BufferFactory.managed().allocate(size)
    }

    /**
     * Allocate buffers from a buffer pool for reuse.
     */
    class FromPool(
        val pool: BufferPool,
    ) : BufferAllocator {
        override fun allocate(size: Int): ReadWriteBuffer = pool.acquire(size)
    }

    companion object {
        /**
         * Default allocator using direct memory.
         */
        val Default: BufferAllocator = Direct
    }
}
