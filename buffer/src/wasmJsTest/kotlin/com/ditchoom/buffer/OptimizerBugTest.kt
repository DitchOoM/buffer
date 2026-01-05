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
        repeat(1000) {
            val (offset, aligned) = LinearMemoryAllocator.allocate(1024)
            // Don't create LinearBuffer, just verify offset is valid
            assertTrue(offset >= 0)
            assertTrue(aligned >= 1024)
        }
    }

    // Test 2: Is it LinearBuffer construction?
    @Test
    fun testLinearBufferConstruction() {
        val (offset, _) = LinearMemoryAllocator.allocate(1024)
        repeat(1000) {
            // Create LinearBuffer with same offset (don't allocate new memory)
            val buffer = LinearBuffer(offset, 1024, ByteOrder.BIG_ENDIAN)
            buffer.writeInt(42)
            buffer.resetForRead()
            assertEquals(42, buffer.readInt())
        }
    }

    // Test 3: Is it the @JsFun calls?
    @Test
    fun testJsFunCalls() {
        // Force initialization
        LinearMemoryAllocator.allocate(16)

        // The allocator calls jsMemoryGrow via @JsFun
        // Test rapid allocations to stress the @JsFun path
        repeat(10000) {
            LinearMemoryAllocator.allocate(16)
        }
    }

    // Test 4: Is it Pair creation?
    @Test
    fun testPairCreation() {
        repeat(100000) {
            val pair = Pair(it, it + 1)
            assertTrue(pair.first == it)
        }
    }

    // Test 5: Is it the Pointer operations?
    @Test
    fun testPointerOperations() {
        val (offset, _) = LinearMemoryAllocator.allocate(8192)
        val buffer = LinearBuffer(offset, 8192, ByteOrder.BIG_ENDIAN)

        repeat(10000) { i ->
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
        repeat(1000) { i ->
            val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)
            buffer.writeInt(i)
            buffer.resetForRead()
            assertEquals(i, buffer.readInt())
        }
    }

    // Test 7: Check if it's the when expression in allocate
    @Test
    fun testWhenExpression() {
        repeat(10000) {
            val zone: AllocationZone = if (it % 2 == 0) AllocationZone.Direct else AllocationZone.Heap
            val buffer = PlatformBuffer.allocate(64, zone)
            buffer.writeByte(1)
        }
    }
}
