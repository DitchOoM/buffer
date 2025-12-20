package com.ditchoom.buffer

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.DataView
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

// Extension functions for Int8Array bulk operations
private fun Int8Array.subarray(
    begin: Int,
    end: Int,
): Int8Array = asDynamic().subarray(begin, end).unsafeCast<Int8Array>()

private fun Int8Array.set(
    array: Int8Array,
    offset: Int,
) {
    asDynamic().set(array, offset)
}

private fun Int8Array.fill(value: Byte) {
    asDynamic().fill(value)
}

/**
 * Holder for buffer views to reduce map lookups from 3 to 1.
 */
private class BufferHolder(
    buffer: ArrayBuffer,
) {
    val dataView = DataView(buffer)
    val int8Array = Int8Array(buffer)
}

actual object UnsafeMemory {
    private var nextId = 1L
    private val holders = mutableMapOf<Long, BufferHolder>()

    actual val nativeByteOrder: ByteOrder =
        run {
            val buffer = ArrayBuffer(4)
            val view = DataView(buffer)
            view.setInt32(0, 0x01020304, false)
            if (view.getInt8(0) == 0x01.toByte()) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        }

    private val isLittleEndian = nativeByteOrder == ByteOrder.LITTLE_ENDIAN

    actual fun allocate(size: Int): Long {
        val id = nextId++
        holders[id] = BufferHolder(ArrayBuffer(size))
        return id
    }

    actual fun free(address: Long) {
        holders.remove(address)
    }

    private fun getHolder(address: Long): BufferHolder = holders[address] ?: error("Invalid address: $address")

    actual fun getByte(
        address: Long,
        offset: Int,
    ): Byte = getHolder(address).dataView.getInt8(offset)

    actual fun putByte(
        address: Long,
        offset: Int,
        value: Byte,
    ) {
        getHolder(address).dataView.setInt8(offset, value)
    }

    actual fun getShort(
        address: Long,
        offset: Int,
    ): Short =
        getHolder(address)
            .dataView
            .getInt16(offset, isLittleEndian)
            .toInt()
            .toShort()

    actual fun putShort(
        address: Long,
        offset: Int,
        value: Short,
    ) {
        getHolder(address).dataView.setInt16(offset, value.toInt().toShort(), isLittleEndian)
    }

    actual fun getInt(
        address: Long,
        offset: Int,
    ): Int = getHolder(address).dataView.getInt32(offset, isLittleEndian)

    actual fun putInt(
        address: Long,
        offset: Int,
        value: Int,
    ) {
        getHolder(address).dataView.setInt32(offset, value, isLittleEndian)
    }

    actual fun getLong(
        address: Long,
        offset: Int,
    ): Long {
        val view = getHolder(address).dataView
        return if (isLittleEndian) {
            val low = view.getInt32(offset, true).toLong() and 0xFFFFFFFFL
            val high = view.getInt32(offset + 4, true).toLong()
            (high shl 32) or low
        } else {
            val high = view.getInt32(offset, false).toLong()
            val low = view.getInt32(offset + 4, false).toLong() and 0xFFFFFFFFL
            (high shl 32) or low
        }
    }

    actual fun putLong(
        address: Long,
        offset: Int,
        value: Long,
    ) {
        val view = getHolder(address).dataView
        val high = (value shr 32).toInt()
        val low = value.toInt()
        if (isLittleEndian) {
            view.setInt32(offset, low, true)
            view.setInt32(offset + 4, high, true)
        } else {
            view.setInt32(offset, high, false)
            view.setInt32(offset + 4, low, false)
        }
    }

    actual fun getFloat(
        address: Long,
        offset: Int,
    ): Float = getHolder(address).dataView.getFloat32(offset, isLittleEndian)

    actual fun putFloat(
        address: Long,
        offset: Int,
        value: Float,
    ) {
        getHolder(address).dataView.setFloat32(offset, value, isLittleEndian)
    }

    actual fun getDouble(
        address: Long,
        offset: Int,
    ): Double = getHolder(address).dataView.getFloat64(offset, isLittleEndian)

    actual fun putDouble(
        address: Long,
        offset: Int,
        value: Double,
    ) {
        getHolder(address).dataView.setFloat64(offset, value, isLittleEndian)
    }

    actual fun copyToArray(
        address: Long,
        offset: Int,
        dest: ByteArray,
        destOffset: Int,
        length: Int,
    ) {
        val srcArray = getHolder(address).int8Array
        // Use native Int8Array.set() for bulk copy - zero-copy cast to Int8Array
        val subArray = srcArray.subarray(offset, offset + length)
        val destInt8 = dest.unsafeCast<Int8Array>()
        destInt8.set(subArray, destOffset)
    }

    actual fun copyFromArray(
        src: ByteArray,
        srcOffset: Int,
        address: Long,
        offset: Int,
        length: Int,
    ) {
        val destArray = getHolder(address).int8Array
        // Use native Int8Array.set() for bulk copy - this is the key optimization!
        val srcInt8 = src.unsafeCast<Int8Array>().subarray(srcOffset, srcOffset + length)
        destArray.set(srcInt8, offset)
    }

    actual fun zeroMemory(
        address: Long,
        offset: Int,
        length: Int,
    ) {
        val array = getHolder(address).int8Array
        // Use fill(0) for efficient bulk zeroing
        val subArray = array.subarray(offset, offset + length)
        subArray.fill(0)
    }
}
