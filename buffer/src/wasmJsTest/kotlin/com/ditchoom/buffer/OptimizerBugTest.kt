package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests to help isolate the WASM optimizer bug that causes stack overflow
 * in production builds with LinearBuffer allocation.
 */
class OptimizerBugTest {
    // Test 1: Is it the allocator or LinearBuffer?
    @Test
    fun testAllocatorOnly() {
        // Just test the allocator without creating LinearBuffer
        repeat(REPEAT_1K) {
            val (offset, aligned) = LinearMemoryAllocator.allocate(BUFFER_1K)
            // Don't create LinearBuffer, just verify offset is valid
            assertTrue(offset >= 0)
            assertTrue(aligned >= BUFFER_1K)
        }
    }

    // Test 2: Is it LinearBuffer construction?
    @Test
    fun testLinearBufferConstruction() {
        val (offset, _) = LinearMemoryAllocator.allocate(BUFFER_1K)
        repeat(REPEAT_1K) {
            // Create LinearBuffer with same offset (don't allocate new memory)
            val buffer = LinearBuffer(offset, BUFFER_1K, ByteOrder.BIG_ENDIAN)
            buffer.writeInt(SENTINEL_VALUE)
            buffer.resetForRead()
            assertEquals(SENTINEL_VALUE, buffer.readInt())
        }
    }

    // Test 3: Is it the @JsFun calls?
    @Test
    fun testJsFunCalls() {
        // Force initialization
        LinearMemoryAllocator.allocate(BUFFER_16)

        // The allocator calls jsMemoryGrow via @JsFun
        // Test rapid allocations to stress the @JsFun path
        repeat(REPEAT_10K) {
            LinearMemoryAllocator.allocate(BUFFER_16)
        }
    }

    // Test 4: Is it Pair creation?
    @Test
    fun testPairCreation() {
        repeat(REPEAT_100K) {
            val pair = Pair(it, it + 1)
            assertTrue(pair.first == it)
        }
    }

    // Test 5: Is it the Pointer operations?
    @Test
    fun testPointerOperations() {
        val (offset, _) = LinearMemoryAllocator.allocate(BUFFER_8K)
        val buffer = LinearBuffer(offset, BUFFER_8K, ByteOrder.BIG_ENDIAN)

        repeat(REPEAT_10K) { i ->
            buffer.resetForWrite()
            // Write operations use Pointer.store*
            buffer.writeInt(i)
            buffer.writeLong(i.toLong())
            buffer.writeShort(i.toShort())
            buffer.writeByte(i.toByte())

            buffer.resetForRead()
            // Read operations use Pointer.load*
            buffer.readInt()
            buffer.readLong()
            buffer.readShort()
            buffer.readByte()
        }
    }

    // Test 6: Full allocation + usage cycle
    @Test
    fun testFullCycle() {
        repeat(REPEAT_1K) { i ->
            val buffer = BufferFactory.Default.allocate(BUFFER_1K)
            buffer.writeInt(i)
            buffer.resetForRead()
            assertEquals(i, buffer.readInt())
        }
    }

    // Test 7: Check if it's the when expression in allocate
    @Test
    fun testWhenExpression() {
        repeat(REPEAT_10K) {
            val factory: BufferFactory = if (it % 2 == 0) BufferFactory.Default else BufferFactory.managed()
            val buffer = factory.allocate(BUFFER_64)
            buffer.writeByte(1)
        }
    }

    private companion object {
        private const val REPEAT_1K = 1000
        private const val REPEAT_10K = 10000
        private const val REPEAT_100K = 100000
        private const val BUFFER_16 = 16
        private const val BUFFER_64 = 64
        private const val BUFFER_1K = 1024
        private const val BUFFER_8K = 8192
        private const val SENTINEL_VALUE = 42
    }
}
