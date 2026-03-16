package com.ditchoom.buffer

import android.os.Parcel

/**
 * Android-specific subclass that provides Parcelable stub implementations.
 * UnsafePlatformBuffer uses explicit memory management and is not parcelable.
 */
class AndroidUnsafePlatformBuffer private constructor(
    nativeAddress: Long,
    capacity: Int,
    byteOrder: ByteOrder,
) : UnsafePlatformBuffer(nativeAddress, capacity, byteOrder) {
    override fun describeContents(): Int = 0

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ): Unit =
        throw UnsupportedOperationException(
            "UnsafePlatformBuffer cannot be parceled. " +
                "Use BufferFactory.shared().allocate() for parcelable buffers.",
        )

    companion object {
        fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): AndroidUnsafePlatformBuffer {
            val address = UnsafeAllocator.allocateMemory(size.toLong())
            UnsafeMemory.setMemory(address, size.toLong(), 0)
            return AndroidUnsafePlatformBuffer(address, size, byteOrder)
        }
    }
}
