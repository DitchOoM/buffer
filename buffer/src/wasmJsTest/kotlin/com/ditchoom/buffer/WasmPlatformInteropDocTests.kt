package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests to validate the WASM-specific documentation samples from docs/docs/platforms/wasm.md
 */
class WasmPlatformInteropDocTests {
    @Test
    fun toNativeDataReturnsLinearBuffer() {
        val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)
        val data = byteArrayOf(1, 2, 3, 4, 5)
        buffer.writeBytes(data)
        buffer.resetForRead()

        // Get LinearBuffer (zero-copy slice)
        val nativeData = buffer.toNativeData()
        val linearBuffer: LinearBuffer = nativeData.linearBuffer

        assertNotNull(linearBuffer)
        assertEquals(5, linearBuffer.remaining())
    }

    @Test
    fun toMutableNativeDataReturnsLinearBuffer() {
        val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)
        val data = byteArrayOf(1, 2, 3, 4, 5)
        buffer.writeBytes(data)
        buffer.resetForRead()

        // Get mutable LinearBuffer
        val mutableData = buffer.toMutableNativeData()
        val linearBuffer: LinearBuffer = mutableData.linearBuffer

        assertNotNull(linearBuffer)
        assertEquals(5, linearBuffer.remaining())
    }

    @Test
    fun linearBufferBaseOffset() {
        val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct) as LinearBuffer
        buffer.writeInt(42)
        buffer.writeString("Hello from WASM")
        buffer.resetForRead()

        val nativeData = buffer.toNativeData()
        val offset = nativeData.linearBuffer.baseOffset

        // Offset should be a valid memory location
        assertTrue(offset >= 0)
    }

    @Test
    fun zeroCopyForLinearBuffer() {
        val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct) as LinearBuffer
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()

        val nativeData = buffer.toNativeData()
        val linearBuffer = nativeData.linearBuffer

        // Should be a slice sharing the same memory
        assertEquals(8, linearBuffer.remaining())
    }

    @Test
    fun byteArrayBufferConversion() {
        // ByteArrayBuffer (Heap) must copy to LinearBuffer
        val buffer = PlatformBuffer.allocate(8, AllocationZone.Heap)
        buffer.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffer.resetForRead()

        val nativeData = buffer.toNativeData()
        assertEquals(8, nativeData.linearBuffer.remaining())
    }

    @Test
    fun positionInvarianceForLinearBuffer() {
        val buffer = PlatformBuffer.allocate(100, AllocationZone.Direct)
        buffer.writeInt(1)
        buffer.writeInt(2)
        buffer.writeInt(3)
        buffer.resetForRead()
        buffer.readInt() // position = 4

        val positionBefore = buffer.position()
        val limitBefore = buffer.limit()

        // Convert to LinearBuffer
        buffer.toNativeData()

        // Position and limit unchanged
        assertEquals(positionBefore, buffer.position())
        assertEquals(limitBefore, buffer.limit())
    }

    @Test
    fun positionInvarianceForMutableLinearBuffer() {
        val buffer = PlatformBuffer.allocate(100, AllocationZone.Direct)
        buffer.writeInt(1)
        buffer.writeInt(2)
        buffer.writeInt(3)
        buffer.resetForRead()
        buffer.readInt() // position = 4

        val positionBefore = buffer.position()
        val limitBefore = buffer.limit()

        // Convert to mutable LinearBuffer
        buffer.toMutableNativeData()

        // Position and limit unchanged
        assertEquals(positionBefore, buffer.position())
        assertEquals(limitBefore, buffer.limit())
    }

    @Test
    fun partialBufferConversion() {
        val buffer = PlatformBuffer.allocate(100, AllocationZone.Direct)
        buffer.writeInt(1)
        buffer.writeInt(2)
        buffer.writeInt(3)
        buffer.resetForRead()
        buffer.position(4) // Skip first int

        val nativeData = buffer.toNativeData()

        // Should have 8 bytes (2 ints)
        assertEquals(8, nativeData.linearBuffer.remaining())
    }
}
