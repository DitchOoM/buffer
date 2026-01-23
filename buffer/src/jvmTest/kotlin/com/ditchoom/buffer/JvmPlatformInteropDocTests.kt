package com.ditchoom.buffer

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests to validate the JVM-specific documentation samples from docs/docs/platforms/jvm.md
 */
class JvmPlatformInteropDocTests {
    @Test
    fun toNativeDataReturnsReadOnlyByteBuffer() {
        val buffer = PlatformBuffer.allocate(1024)
        val data = byteArrayOf(1, 2, 3, 4, 5)
        buffer.writeBytes(data)
        buffer.resetForRead()

        // Get read-only ByteBuffer (zero-copy duplicate)
        val nativeData = buffer.toNativeData()
        val readOnlyBuffer: ByteBuffer = nativeData.byteBuffer

        assertNotNull(readOnlyBuffer)
        assertEquals(5, readOnlyBuffer.remaining())

        // Read data from ByteBuffer
        val readData = ByteArray(5)
        readOnlyBuffer.get(readData)
        assertEquals(data.toList(), readData.toList())
    }

    @Test
    fun toMutableNativeDataReturnsMutableByteBuffer() {
        val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)
        val data = byteArrayOf(1, 2, 3, 4, 5)
        buffer.writeBytes(data)
        buffer.resetForRead()

        // Get mutable ByteBuffer (zero-copy duplicate for direct buffers)
        val mutableData = buffer.toMutableNativeData()
        val mutableBuffer: ByteBuffer = mutableData.byteBuffer

        assertNotNull(mutableBuffer)
        assertEquals(5, mutableBuffer.remaining())
        assertEquals(true, mutableBuffer.isDirect)

        // Can modify the ByteBuffer - changes reflect in original (zero-copy)
        mutableBuffer.put(0, 99.toByte())
        buffer.position(0)
        assertEquals(99.toByte(), buffer.readByte())
    }

    @Test
    fun directBufferZeroCopyConversion() {
        val buffer = PlatformBuffer.allocate(1024, AllocationZone.Direct)
        buffer.writeInt(42)
        buffer.resetForRead()

        val nativeData = buffer.toNativeData()
        val byteBuffer = nativeData.byteBuffer

        // Should be a direct buffer
        assertEquals(true, byteBuffer.isDirect)

        // Read the int
        assertEquals(42, byteBuffer.int)
    }

    @Test
    fun heapBufferConversion() {
        val buffer = PlatformBuffer.allocate(1024, AllocationZone.Heap)
        buffer.writeInt(42)
        buffer.resetForRead()

        val nativeData = buffer.toNativeData()
        val byteBuffer = nativeData.byteBuffer

        // toNativeData() always returns a direct buffer for native interop
        // (heap buffers are copied to direct memory)
        assertEquals(true, byteBuffer.isDirect)

        // Read the int
        assertEquals(42, byteBuffer.int)
    }

    @Test
    fun partialBufferConversion() {
        val buffer = PlatformBuffer.allocate(1024)
        buffer.writeInt(1)
        buffer.writeInt(2)
        buffer.writeInt(3)
        buffer.resetForRead()

        // Skip first int
        buffer.position(4)

        val nativeData = buffer.toNativeData()
        val byteBuffer = nativeData.byteBuffer

        // Should have remaining 2 ints
        assertEquals(8, byteBuffer.remaining())
        assertEquals(2, byteBuffer.int)
        assertEquals(3, byteBuffer.int)
    }

    @Test
    fun jniDocumentationExample() {
        // Direct buffers: zero-copy - changes to NIO buffer reflect in original
        val direct = PlatformBuffer.allocate(1024, AllocationZone.Direct)
        direct.writeInt(42)
        direct.resetForRead()
        val directNio = direct.toMutableNativeData().byteBuffer
        assertEquals(true, directNio.isDirect)

        // Modify via NIO buffer - should reflect in original (zero-copy)
        directNio.putInt(0, 99)
        direct.position(0)
        assertEquals(99, direct.readInt())

        // Heap buffers: copied to direct for native interop
        val heap = PlatformBuffer.allocate(1024, AllocationZone.Heap)
        heap.writeInt(42)
        heap.resetForRead()
        val heapNio = heap.toNativeData().byteBuffer
        // toNativeData() always returns direct buffer for native memory access
        assertEquals(true, heapNio.isDirect)
        assertEquals(42, heapNio.int)
    }
}
