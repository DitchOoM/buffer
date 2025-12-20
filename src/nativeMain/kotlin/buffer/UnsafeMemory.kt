@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.buffer

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import platform.posix.malloc
import platform.posix.memcpy
import platform.posix.memset
import platform.posix.free as posixFree

actual object UnsafeMemory {
    actual val nativeByteOrder: ByteOrder =
        run {
            val testValue = 0x01020304
            val ptr = nativeHeap.alloc<IntVar>()
            ptr.value = testValue
            val firstByte = ptr.ptr.reinterpret<ByteVar>()[0]
            nativeHeap.free(ptr)
            if (firstByte == 0x04.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        }

    actual fun allocate(size: Int): Long {
        val ptr = malloc(size.toULong())
        if (ptr != null) {
            memset(ptr, 0, size.toULong())
        }
        return ptr.toLong()
    }

    actual fun free(address: Long) {
        val ptr = address.toCPointer<ByteVar>()
        posixFree(ptr)
    }

    actual fun getByte(
        address: Long,
        offset: Int,
    ): Byte {
        val ptr = address.toCPointer<ByteVar>()!!
        return ptr[offset]
    }

    actual fun putByte(
        address: Long,
        offset: Int,
        value: Byte,
    ) {
        val ptr = address.toCPointer<ByteVar>()!!
        ptr[offset] = value
    }

    actual fun getShort(
        address: Long,
        offset: Int,
    ): Short {
        val ptr = address.toCPointer<ByteVar>()!!.plus(offset)!!
        return ptr.reinterpret<ShortVar>()[0]
    }

    actual fun putShort(
        address: Long,
        offset: Int,
        value: Short,
    ) {
        val ptr = address.toCPointer<ByteVar>()!!.plus(offset)!!
        ptr.reinterpret<ShortVar>()[0] = value
    }

    actual fun getInt(
        address: Long,
        offset: Int,
    ): Int {
        val ptr = address.toCPointer<ByteVar>()!!.plus(offset)!!
        return ptr.reinterpret<IntVar>()[0]
    }

    actual fun putInt(
        address: Long,
        offset: Int,
        value: Int,
    ) {
        val ptr = address.toCPointer<ByteVar>()!!.plus(offset)!!
        ptr.reinterpret<IntVar>()[0] = value
    }

    actual fun getLong(
        address: Long,
        offset: Int,
    ): Long {
        val ptr = address.toCPointer<ByteVar>()!!.plus(offset)!!
        return ptr.reinterpret<LongVar>()[0]
    }

    actual fun putLong(
        address: Long,
        offset: Int,
        value: Long,
    ) {
        val ptr = address.toCPointer<ByteVar>()!!.plus(offset)!!
        ptr.reinterpret<LongVar>()[0] = value
    }

    actual fun getFloat(
        address: Long,
        offset: Int,
    ): Float {
        val ptr = address.toCPointer<ByteVar>()!!.plus(offset)!!
        return ptr.reinterpret<FloatVar>()[0]
    }

    actual fun putFloat(
        address: Long,
        offset: Int,
        value: Float,
    ) {
        val ptr = address.toCPointer<ByteVar>()!!.plus(offset)!!
        ptr.reinterpret<FloatVar>()[0] = value
    }

    actual fun getDouble(
        address: Long,
        offset: Int,
    ): Double {
        val ptr = address.toCPointer<ByteVar>()!!.plus(offset)!!
        return ptr.reinterpret<DoubleVar>()[0]
    }

    actual fun putDouble(
        address: Long,
        offset: Int,
        value: Double,
    ) {
        val ptr = address.toCPointer<ByteVar>()!!.plus(offset)!!
        ptr.reinterpret<DoubleVar>()[0] = value
    }

    actual fun copyToArray(
        address: Long,
        offset: Int,
        dest: ByteArray,
        destOffset: Int,
        length: Int,
    ) {
        val ptr = address.toCPointer<ByteVar>()!!.plus(offset)
        memcpy(dest.refTo(destOffset), ptr, length.toULong())
    }

    actual fun copyFromArray(
        src: ByteArray,
        srcOffset: Int,
        address: Long,
        offset: Int,
        length: Int,
    ) {
        val ptr = address.toCPointer<ByteVar>()!!.plus(offset)
        memcpy(ptr, src.refTo(srcOffset), length.toULong())
    }

    actual fun zeroMemory(
        address: Long,
        offset: Int,
        length: Int,
    ) {
        val ptr = address.toCPointer<ByteVar>()!!.plus(offset)
        memset(ptr, 0, length.toULong())
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : kotlinx.cinterop.CPointed> Long.toCPointer(): CPointer<T>? =
        kotlinx.cinterop.interpretCPointer(kotlinx.cinterop.NativePtr.NULL + this)
}
