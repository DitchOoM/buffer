package com.ditchoom.buffer

import kotlin.test.Test

class BufferUtilsTests {
    @Test
    fun freeIfNeededOnPlatformBuffer() {
        val buffer = BufferFactory.Default.allocate(64)
        buffer.writeInt(0x12345678)
        // Should not crash
        buffer.freeIfNeeded()
    }

    @Test
    fun freeIfNeededOnHeapBuffer() {
        val buffer = BufferFactory.managed().allocate(64)
        buffer.writeInt(0x12345678)
        // Should not crash (no-op or safe free)
        buffer.freeIfNeeded()
    }

    @Test
    fun freeAllOnMixedList() {
        val buffers =
            listOf(
                BufferFactory.Default.allocate(64) as ReadBuffer,
                BufferFactory.managed().allocate(64) as ReadBuffer,
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
