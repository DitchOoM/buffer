package com.ditchoom.buffer.compression

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managedMemoryAccess
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.pool.BufferPool
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies that BufferAllocator implementations produce buffers with either
 * nativeMemoryAccess or managedMemoryAccess, which is required by the Linux
 * zlib withInputPointer() and allocateOutputBuffer() code paths.
 */
class BufferAllocatorAssumptionTests {
    @Test
    fun directAllocatorOutputHasMemoryAccess() {
        val allocator = BufferAllocator.Direct
        val buffer = allocator.allocate(64)
        val readBuf = buffer as ReadBuffer
        val hasAccess = readBuf.nativeMemoryAccess != null || readBuf.managedMemoryAccess != null
        assertTrue(hasAccess, "BufferAllocator.Direct should produce buffers with nativeMemoryAccess or managedMemoryAccess")
    }

    @Test
    fun poolAllocatorOutputHasMemoryAccess() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 4)
        val allocator = BufferAllocator.FromPool(pool)
        val buffer = allocator.allocate(64)
        val readBuf = buffer as ReadBuffer
        val hasAccess = readBuf.nativeMemoryAccess != null || readBuf.managedMemoryAccess != null
        assertTrue(hasAccess, "BufferAllocator.FromPool should produce buffers with nativeMemoryAccess or managedMemoryAccess")
        pool.release(buffer)
        pool.clear()
    }
}
