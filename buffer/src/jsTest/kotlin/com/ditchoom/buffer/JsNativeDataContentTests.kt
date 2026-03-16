package com.ditchoom.buffer

import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * JS-specific content validation tests for toNativeData/toMutableNativeData.
 * Reads actual bytes back from ArrayBuffer/Int8Array to verify correctness.
 */
class JsNativeDataContentTests {
    @Test
    fun toNativeDataPreservesContent() {
        val data = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
        val buf = BufferFactory.Default.allocate(data.size)
        buf.writeBytes(data)
        buf.resetForRead()

        val nativeData = buf.toNativeData()
        val arrayBuffer = nativeData.arrayBuffer
        assertNotNull(arrayBuffer)
        assertEquals(data.size, arrayBuffer.byteLength)

        // Read content back via Int8Array view
        val view = Int8Array(arrayBuffer)
        val readBack = ByteArray(data.size) { view[it] }
        assertContentEquals(data, readBack)
    }

    @Test
    fun toMutableNativeDataPreservesContent() {
        val data = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val buf = BufferFactory.Default.allocate(data.size)
        buf.writeBytes(data)
        buf.resetForRead()

        val mutableData = buf.toMutableNativeData()
        val int8Array = mutableData.int8Array
        assertNotNull(int8Array)
        assertEquals(data.size, int8Array.length)

        val readBack = ByteArray(data.size) { int8Array[it] }
        assertContentEquals(data, readBack)
    }

    @Test
    fun toNativeDataPartialBufferPreservesContent() {
        val buf = BufferFactory.Default.allocate(8)
        buf.writeInt(0x01020304)
        buf.writeInt(0x05060708)
        buf.resetForRead()
        buf.position(4) // Skip first int

        val nativeData = buf.toNativeData()
        val view = Int8Array(nativeData.arrayBuffer)
        assertEquals(4, view.length)
        assertEquals(0x05.toByte(), view[0])
        assertEquals(0x06.toByte(), view[1])
        assertEquals(0x07.toByte(), view[2])
        assertEquals(0x08.toByte(), view[3])
    }

    @Test
    fun toMutableNativeDataMutationReflectsInJsBuffer() {
        val buf = BufferFactory.Default.allocate(4)
        buf.writeBytes(byteArrayOf(1, 2, 3, 4))
        buf.resetForRead()

        val mutableData = buf.toMutableNativeData()
        val int8Array = mutableData.int8Array

        // Mutate via Int8Array
        int8Array.asDynamic()[0] = 99.toByte()

        // If zero-copy, original buffer should reflect the change
        if (buf is JsBuffer) {
            buf.position(0)
            assertEquals(99.toByte(), buf.readByte())
        }
    }

    @Test
    fun toNativeDataFromManagedBufferPreservesContent() {
        val data = byteArrayOf(10, 20, 30, 40, 50)
        val buf = BufferFactory.managed().allocate(data.size)
        buf.writeBytes(data)
        buf.resetForRead()

        val nativeData = buf.toNativeData()
        val view = Int8Array(nativeData.arrayBuffer)
        val readBack = ByteArray(data.size) { view[it] }
        assertContentEquals(data, readBack)
    }

    @Test
    fun zeroLengthToNativeData() {
        val buf = BufferFactory.Default.allocate(0)
        buf.resetForRead()
        val nativeData = buf.toNativeData()
        assertEquals(0, nativeData.arrayBuffer.byteLength)
    }

    @Test
    fun intRoundTripViaNativeData() {
        val buf = BufferFactory.Default.allocate(4)
        buf.writeInt(0xDEADBEEF.toInt())
        buf.resetForRead()

        val nativeData = buf.toNativeData()
        // Wrap the ArrayBuffer back into a buffer and read the int
        val readBack = JsBuffer(Int8Array(nativeData.arrayBuffer), ByteOrder.BIG_ENDIAN)
        assertEquals(0xDEADBEEF.toInt(), readBack.readInt())
    }
}
