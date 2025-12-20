package com.ditchoom.buffer

import sun.misc.Unsafe
import java.lang.reflect.Field

actual object UnsafeMemory {
    private val unsafe: Unsafe =
        run {
            val field: Field = Unsafe::class.java.getDeclaredField("theUnsafe")
            field.isAccessible = true
            field.get(null) as Unsafe
        }

    private val BYTE_ARRAY_OFFSET: Long = unsafe.arrayBaseOffset(ByteArray::class.java).toLong()

    actual val nativeByteOrder: ByteOrder =
        if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.BIG_ENDIAN) {
            ByteOrder.BIG_ENDIAN
        } else {
            ByteOrder.LITTLE_ENDIAN
        }

    actual fun allocate(size: Int): Long {
        val address = unsafe.allocateMemory(size.toLong())
        unsafe.setMemory(address, size.toLong(), 0)
        return address
    }

    actual fun free(address: Long) {
        unsafe.freeMemory(address)
    }

    actual fun getByte(
        address: Long,
        offset: Int,
    ): Byte = unsafe.getByte(address + offset)

    actual fun putByte(
        address: Long,
        offset: Int,
        value: Byte,
    ) {
        unsafe.putByte(address + offset, value)
    }

    actual fun getShort(
        address: Long,
        offset: Int,
    ): Short = unsafe.getShort(address + offset)

    actual fun putShort(
        address: Long,
        offset: Int,
        value: Short,
    ) {
        unsafe.putShort(address + offset, value)
    }

    actual fun getInt(
        address: Long,
        offset: Int,
    ): Int = unsafe.getInt(address + offset)

    actual fun putInt(
        address: Long,
        offset: Int,
        value: Int,
    ) {
        unsafe.putInt(address + offset, value)
    }

    actual fun getLong(
        address: Long,
        offset: Int,
    ): Long = unsafe.getLong(address + offset)

    actual fun putLong(
        address: Long,
        offset: Int,
        value: Long,
    ) {
        unsafe.putLong(address + offset, value)
    }

    actual fun getFloat(
        address: Long,
        offset: Int,
    ): Float = unsafe.getFloat(address + offset)

    actual fun putFloat(
        address: Long,
        offset: Int,
        value: Float,
    ) {
        unsafe.putFloat(address + offset, value)
    }

    actual fun getDouble(
        address: Long,
        offset: Int,
    ): Double = unsafe.getDouble(address + offset)

    actual fun putDouble(
        address: Long,
        offset: Int,
        value: Double,
    ) {
        unsafe.putDouble(address + offset, value)
    }

    actual fun copyToArray(
        address: Long,
        offset: Int,
        dest: ByteArray,
        destOffset: Int,
        length: Int,
    ) {
        unsafe.copyMemory(null, address + offset, dest, BYTE_ARRAY_OFFSET + destOffset, length.toLong())
    }

    actual fun copyFromArray(
        src: ByteArray,
        srcOffset: Int,
        address: Long,
        offset: Int,
        length: Int,
    ) {
        unsafe.copyMemory(src, BYTE_ARRAY_OFFSET + srcOffset, null, address + offset, length.toLong())
    }

    actual fun zeroMemory(
        address: Long,
        offset: Int,
        length: Int,
    ) {
        unsafe.setMemory(address + offset, length.toLong(), 0)
    }
}
