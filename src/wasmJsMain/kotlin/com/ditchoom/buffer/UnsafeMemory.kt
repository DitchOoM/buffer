package com.ditchoom.buffer

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.DataView
import org.khronos.webgl.Int8Array

/**
 * WASM-JS UnsafeMemory implementation using JavaScript typed arrays.
 *
 * Uses DataView operations for multi-byte access which provides
 * efficient native-like memory access.
 */
actual object UnsafeMemory {
    private var nextId = 1L
    private val buffers = mutableMapOf<Long, Int8Array>()
    private val dataViews = mutableMapOf<Long, DataView>()

    actual val nativeByteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN

    actual fun allocate(size: Int): Long {
        val id = nextId++
        val arrayBuffer = ArrayBuffer(size)
        val int8Array = Int8Array(arrayBuffer)
        buffers[id] = int8Array
        dataViews[id] = DataView(arrayBuffer)
        return id
    }

    actual fun free(address: Long) {
        buffers.remove(address)
        dataViews.remove(address)
    }

    private fun getDataView(address: Long): DataView = dataViews[address] ?: error("Invalid address: $address")

    actual fun getByte(
        address: Long,
        offset: Int,
    ): Byte = getDataView(address).getInt8(offset)

    actual fun putByte(
        address: Long,
        offset: Int,
        value: Byte,
    ) {
        getDataView(address).setInt8(offset, value)
    }

    actual fun getShort(
        address: Long,
        offset: Int,
    ): Short = getDataView(address).getInt16(offset, true).toInt().toShort() // native endian (little)

    actual fun putShort(
        address: Long,
        offset: Int,
        value: Short,
    ) {
        getDataView(address).setInt16(offset, value, true)
    }

    actual fun getInt(
        address: Long,
        offset: Int,
    ): Int = getDataView(address).getInt32(offset, true)

    actual fun putInt(
        address: Long,
        offset: Int,
        value: Int,
    ) {
        getDataView(address).setInt32(offset, value, true)
    }

    actual fun getLong(
        address: Long,
        offset: Int,
    ): Long {
        val dv = getDataView(address)
        val low = dv.getInt32(offset, true).toLong() and 0xFFFFFFFFL
        val high = dv.getInt32(offset + 4, true).toLong() and 0xFFFFFFFFL
        return (high shl 32) or low
    }

    actual fun putLong(
        address: Long,
        offset: Int,
        value: Long,
    ) {
        val dv = getDataView(address)
        dv.setInt32(offset, value.toInt(), true)
        dv.setInt32(offset + 4, (value shr 32).toInt(), true)
    }

    actual fun getFloat(
        address: Long,
        offset: Int,
    ): Float = Float.fromBits(getInt(address, offset))

    actual fun putFloat(
        address: Long,
        offset: Int,
        value: Float,
    ) {
        putInt(address, offset, value.toRawBits())
    }

    actual fun getDouble(
        address: Long,
        offset: Int,
    ): Double = Double.fromBits(getLong(address, offset))

    actual fun putDouble(
        address: Long,
        offset: Int,
        value: Double,
    ) {
        putLong(address, offset, value.toRawBits())
    }

    actual fun copyToArray(
        address: Long,
        offset: Int,
        dest: ByteArray,
        destOffset: Int,
        length: Int,
    ) {
        val dv = getDataView(address)
        for (i in 0 until length) {
            dest[destOffset + i] = dv.getInt8(offset + i)
        }
    }

    actual fun copyFromArray(
        src: ByteArray,
        srcOffset: Int,
        address: Long,
        offset: Int,
        length: Int,
    ) {
        val dv = getDataView(address)
        for (i in 0 until length) {
            dv.setInt8(offset + i, src[srcOffset + i])
        }
    }

    actual fun zeroMemory(
        address: Long,
        offset: Int,
        length: Int,
    ) {
        val dv = getDataView(address)
        for (i in 0 until length) {
            dv.setInt8(offset + i, 0)
        }
    }
}
