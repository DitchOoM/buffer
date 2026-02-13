package com.ditchoom.buffer.compression

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.pool.BufferPool
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Verifies that BufferAllocator implementations produce buffers with
 * nativeMemoryAccess, which is required by the Linux zlib withInputPointer().
 */
class BufferAllocatorAssumptionTests {
    @Test
    fun directAllocatorOutputHasNativeMemoryAccess() {
        val allocator = BufferAllocator.Direct
        val buffer = allocator.allocate(64)
        assertNotNull(
            (buffer as ReadBuffer).nativeMemoryAccess,
            "BufferAllocator.Direct should produce buffers with nativeMemoryAccess",
        )
    }

    @Test
    fun poolAllocatorOutputHasNativeMemoryAccess() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 4)
        val allocator = BufferAllocator.FromPool(pool)
        val buffer = allocator.allocate(64)
        assertNotNull(
            (buffer as ReadBuffer).nativeMemoryAccess,
            "BufferAllocator.FromPool should produce buffers with nativeMemoryAccess",
        )
        pool.release(buffer)
        pool.clear()
    }
}
