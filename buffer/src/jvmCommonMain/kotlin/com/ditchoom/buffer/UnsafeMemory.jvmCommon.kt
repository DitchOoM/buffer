@file:Suppress("UNCHECKED_CAST")

package com.ditchoom.buffer

import sun.misc.Unsafe

actual object UnsafeMemory {
    private val unsafe: Unsafe? =
        try {
            val field = Unsafe::class.java.getDeclaredField("theUnsafe")
            field.isAccessible = true
            field.get(null) as Unsafe
        } catch (e: Exception) {
            null
        }

    actual val isSupported: Boolean = unsafe != null

    private fun checkSupported() {
        if (unsafe == null) {
            throw UnsupportedOperationException(
                "UnsafeMemory is not supported on this platform. sun.misc.Unsafe is not available.",
            )
        }
    }

    actual fun getByte(address: Long): Byte {
        checkSupported()
        return unsafe!!.getByte(address)
    }

    actual fun putByte(
        address: Long,
        value: Byte,
    ) {
        checkSupported()
        unsafe!!.putByte(address, value)
    }

    actual fun getShort(address: Long): Short {
        checkSupported()
        return unsafe!!.getShort(address)
    }

    actual fun putShort(
        address: Long,
        value: Short,
    ) {
        checkSupported()
        unsafe!!.putShort(address, value)
    }

    actual fun getInt(address: Long): Int {
        checkSupported()
        return unsafe!!.getInt(address)
    }

    actual fun putInt(
        address: Long,
        value: Int,
    ) {
        checkSupported()
        unsafe!!.putInt(address, value)
    }

    actual fun getLong(address: Long): Long {
        checkSupported()
        return unsafe!!.getLong(address)
    }

    actual fun putLong(
        address: Long,
        value: Long,
    ) {
        checkSupported()
        unsafe!!.putLong(address, value)
    }

    actual fun copyMemory(
        srcAddress: Long,
        dstAddress: Long,
        size: Long,
    ) {
        checkSupported()
        unsafe!!.copyMemory(srcAddress, dstAddress, size)
    }

    actual fun setMemory(
        address: Long,
        size: Long,
        value: Byte,
    ) {
        checkSupported()
        unsafe!!.setMemory(address, size, value)
    }
}
