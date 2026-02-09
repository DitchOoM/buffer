package com.ditchoom.buffer.compression

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadWriteBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.pool.BufferPool
import kotlin.jvm.JvmInline

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
     * Allocate buffers from a specific memory zone.
     * Use Direct for I/O operations, Heap for compute-heavy operations.
     */
    @JvmInline
    value class FromZone(
        val zone: AllocationZone = AllocationZone.Direct,
    ) : BufferAllocator {
        override fun allocate(size: Int): ReadWriteBuffer = PlatformBuffer.allocate(size, zone)
    }

    /**
     * Allocate buffers using direct memory (default).
     */
    data object Direct : BufferAllocator {
        override fun allocate(size: Int): ReadWriteBuffer = PlatformBuffer.allocate(size, AllocationZone.Direct)
    }

    /**
     * Allocate buffers using heap memory.
     */
    data object Heap : BufferAllocator {
        override fun allocate(size: Int): ReadWriteBuffer = PlatformBuffer.allocate(size, AllocationZone.Heap)
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
