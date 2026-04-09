package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managedMemoryAccess
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.pool.BufferPool
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies that BufferFactory implementations produce buffers with either
 * nativeMemoryAccess or managedMemoryAccess, which is required by the Linux
 * zlib withInputPointer() and allocateOutputBuffer() code paths.
 */
class BufferAllocatorAssumptionTests {
    @Test
    fun defaultFactoryOutputHasMemoryAccess() {
        val factory = BufferFactory.Default
        val buffer = factory.allocate(64)
        val readBuf = buffer as ReadBuffer
        val hasAccess = readBuf.nativeMemoryAccess != null || readBuf.managedMemoryAccess != null
        assertTrue(hasAccess, "BufferFactory.Default should produce buffers with nativeMemoryAccess or managedMemoryAccess")
    }

    @Test
    fun poolFactoryOutputHasMemoryAccess() {
        val pool = BufferPool(defaultBufferSize = 1024, maxPoolSize = 4)
        val factory = pool
        val buffer = factory.allocate(64)
        val readBuf = buffer as ReadBuffer
        val hasAccess = readBuf.nativeMemoryAccess != null || readBuf.managedMemoryAccess != null
        assertTrue(hasAccess, "pool should produce buffers with nativeMemoryAccess or managedMemoryAccess")
        pool.release(buffer)
        pool.clear()
    }
}
