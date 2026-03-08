package com.ditchoom.buffer

/**
 * JVM-specific concrete subclass of [UnsafePlatformBuffer].
 * On JVM, Parcelable is an empty interface so no additional methods are needed.
 */
internal class JvmUnsafePlatformBuffer(
    nativeAddress: Long,
    capacity: Int,
    byteOrder: ByteOrder,
) : UnsafePlatformBuffer(nativeAddress, capacity, byteOrder) {
    companion object {
        fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): JvmUnsafePlatformBuffer {
            val address = UnsafeAllocator.allocateMemory(size.toLong())
            UnsafeMemory.setMemory(address, size.toLong(), 0)
            return JvmUnsafePlatformBuffer(address, size, byteOrder)
        }
    }
}
