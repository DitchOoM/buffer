@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)

package com.ditchoom.buffer

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import platform.posix.memset

actual object UnsafeMemory {
    actual val isSupported: Boolean = true

    actual fun getByte(address: Long): Byte {
        val ptr = address.toCPointer<ByteVar>()!!
        return ptr[0]
    }

    actual fun putByte(
        address: Long,
        value: Byte,
    ) {
        val ptr = address.toCPointer<ByteVar>()!!
        ptr[0] = value
    }

    actual fun getShort(address: Long): Short {
        val ptr = address.toCPointer<ShortVar>()!!
        return ptr[0]
    }

    actual fun putShort(
        address: Long,
        value: Short,
    ) {
        val ptr = address.toCPointer<ShortVar>()!!
        ptr[0] = value
    }

    actual fun getInt(address: Long): Int {
        val ptr = address.toCPointer<IntVar>()!!
        return ptr[0]
    }

    actual fun putInt(
        address: Long,
        value: Int,
    ) {
        val ptr = address.toCPointer<IntVar>()!!
        ptr[0] = value
    }

    actual fun getLong(address: Long): Long {
        val ptr = address.toCPointer<LongVar>()!!
        return ptr[0]
    }

    actual fun putLong(
        address: Long,
        value: Long,
    ) {
        val ptr = address.toCPointer<LongVar>()!!
        ptr[0] = value
    }

    actual fun copyMemory(
        srcAddress: Long,
        dstAddress: Long,
        size: Long,
    ) {
        memcpy(dstAddress.toCPointer<ByteVar>(), srcAddress.toCPointer<ByteVar>(), size.convert())
    }

    actual fun setMemory(
        address: Long,
        size: Long,
        value: Byte,
    ) {
        memset(address.toCPointer<ByteVar>(), value.toInt(), size.convert())
    }

    actual fun copyMemoryToArray(
        srcAddress: Long,
        dest: ByteArray,
        destOffset: Int,
        length: Int,
    ) {
        dest.usePinned { pinned ->
            memcpy(pinned.addressOf(destOffset), srcAddress.toCPointer<ByteVar>(), length.convert())
        }
    }

    actual fun copyMemoryFromArray(
        src: ByteArray,
        srcOffset: Int,
        dstAddress: Long,
        length: Int,
    ) {
        src.usePinned { pinned ->
            memcpy(dstAddress.toCPointer<ByteVar>(), pinned.addressOf(srcOffset), length.convert())
        }
    }
}
