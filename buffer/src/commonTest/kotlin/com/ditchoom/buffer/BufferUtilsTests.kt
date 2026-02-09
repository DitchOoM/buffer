package com.ditchoom.buffer

import kotlin.test.Test

class BufferUtilsTests {
    @Test
    fun freeIfNeededOnPlatformBuffer() {
        val buffer = PlatformBuffer.allocate(64, AllocationZone.Direct)
        buffer.writeInt(0x12345678)
        // Should not crash
        buffer.freeIfNeeded()
    }

    @Test
    fun freeIfNeededOnHeapBuffer() {
        val buffer = PlatformBuffer.allocate(64, AllocationZone.Heap)
        buffer.writeInt(0x12345678)
        // Should not crash (no-op or safe free)
        buffer.freeIfNeeded()
    }

    @Test
    fun freeAllOnMixedList() {
        val buffers =
            listOf(
                PlatformBuffer.allocate(64, AllocationZone.Direct) as ReadBuffer,
                PlatformBuffer.allocate(64, AllocationZone.Heap) as ReadBuffer,
            )
        // Should not crash
        buffers.freeAll()
    }

    @Test
    fun freeAllOnEmptyList() {
        val buffers = emptyList<ReadBuffer>()
        // Should not crash
        buffers.freeAll()
    }
}
