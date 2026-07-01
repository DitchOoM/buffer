package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals

class CountingBufferFactoryTest {
    @Test
    fun countsAllocations() {
        val counting = BufferFactory.Default.counting()
        assertEquals(0L, counting.allocationCount)
        counting.allocate(16)
        counting.allocate(64)
        counting.allocate(32)
        assertEquals(3L, counting.allocationCount)
        assertEquals(112L, counting.allocatedBytes)
        assertEquals(64, counting.largestAllocationSize)
        assertEquals(0L, counting.wrapCount)
    }

    @Test
    fun countsWraps() {
        val counting = BufferFactory.Default.counting()
        counting.wrap(byteArrayOf(1, 2, 3))
        assertEquals(1L, counting.wrapCount)
        assertEquals(0L, counting.allocationCount)
        assertEquals(0L, counting.allocatedBytes)
    }

    @Test
    fun resetZeroesAllCounters() {
        val counting = BufferFactory.managed().counting()
        counting.allocate(128)
        counting.wrap(byteArrayOf(1))
        counting.reset()
        assertEquals(0L, counting.allocationCount)
        assertEquals(0L, counting.allocatedBytes)
        assertEquals(0, counting.largestAllocationSize)
        assertEquals(0L, counting.wrapCount)
    }

    @Test
    fun producedBuffersComeFromTheDelegateUnchanged() {
        val counting = BufferFactory.managed().counting()
        val buffer = counting.allocate(8)
        buffer.writeLong(0x1122334455667788L)
        buffer.resetForRead()
        assertEquals(0x1122334455667788L, buffer.readLong())

        val wrapped = counting.wrap(byteArrayOf(7, 8, 9))
        assertEquals(7, wrapped.readByte())
    }

    @Test
    fun composesWithOtherDecorators() {
        val counting = BufferFactory.Default.counting()
        val limited = counting.withSizeLimit(1024)
        limited.allocate(512)
        assertEquals(1L, counting.allocationCount)
        assertEquals(512L, counting.allocatedBytes)
    }
}
