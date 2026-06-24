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
        val buffer = BufferFactory.Default.allocate(BUFFER_SIZE)
        val data = byteArrayOf(1, 2, 3, 4, 5)
        buffer.writeBytes(data)
        buffer.resetForRead()

        // Get LinearBuffer (zero-copy slice)
        val nativeData = buffer.toNativeData()
        val linearBuffer: LinearBuffer = nativeData.linearBuffer

        assertNotNull(linearBuffer)
        assertEquals(FIVE, linearBuffer.remaining())
    }

    @Test
    fun toMutableNativeDataReturnsLinearBuffer() {
        val buffer = BufferFactory.Default.allocate(BUFFER_SIZE)
        val data = byteArrayOf(1, 2, 3, 4, 5)
        buffer.writeBytes(data)
        buffer.resetForRead()

        // Get mutable LinearBuffer
        val mutableData = buffer.toMutableNativeData()
        val linearBuffer: LinearBuffer = mutableData.linearBuffer

        assertNotNull(linearBuffer)
        assertEquals(FIVE, linearBuffer.remaining())
    }

    @Test
    fun linearBufferBaseOffset() {
        val buffer = BufferFactory.Default.allocate(BUFFER_SIZE) as LinearBuffer
        buffer.writeInt(SENTINEL_VALUE)
        buffer.writeString("Hello from WASM")
        buffer.resetForRead()

        val nativeData = buffer.toNativeData()
        val offset = nativeData.linearBuffer.baseOffset

        // Offset should be a valid memory location
        assertTrue(offset >= 0)
    }

    @Test
    fun zeroCopyForLinearBuffer() {
        val buffer = BufferFactory.Default.allocate(BUFFER_SIZE) as LinearBuffer
        buffer.writeBytes(sequentialBytes)
        buffer.resetForRead()

        val nativeData = buffer.toNativeData()
        val linearBuffer = nativeData.linearBuffer

        // Should be a slice sharing the same memory
        assertEquals(EIGHT, linearBuffer.remaining())
    }

    @Test
    fun byteArrayBufferConversion() {
        // ByteArrayBuffer (Heap) must copy to LinearBuffer
        val buffer = BufferFactory.managed().allocate(LEN_8)
        buffer.writeBytes(sequentialBytes)
        buffer.resetForRead()

        val nativeData = buffer.toNativeData()
        assertEquals(EIGHT, nativeData.linearBuffer.remaining())
    }

    @Test
    fun positionInvarianceForLinearBuffer() {
        val buffer = BufferFactory.Default.allocate(LARGE_BUFFER_SIZE)
        buffer.writeInt(1)
        buffer.writeInt(2)
        buffer.writeInt(THREE)
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
        val buffer = BufferFactory.Default.allocate(LARGE_BUFFER_SIZE)
        buffer.writeInt(1)
        buffer.writeInt(2)
        buffer.writeInt(THREE)
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
        val buffer = BufferFactory.Default.allocate(LARGE_BUFFER_SIZE)
        buffer.writeInt(1)
        buffer.writeInt(2)
        buffer.writeInt(THREE)
        buffer.resetForRead()
        buffer.position(INT_SIZE) // Skip first int

        val nativeData = buffer.toNativeData()

        // Should have 8 bytes (2 ints)
        assertEquals(TWO_INTS_BYTES, nativeData.linearBuffer.remaining())
    }

    private companion object {
        private const val THREE = 3
        private const val INT_SIZE = 4
        private const val FIVE = 5
        private const val EIGHT = 8
        private const val LEN_8 = 8
        private const val TWO_INTS_BYTES = 8
        private const val LARGE_BUFFER_SIZE = 100
        private const val BUFFER_SIZE = 1024
        private const val SENTINEL_VALUE = 42

        private val sequentialBytes: ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    }
}
