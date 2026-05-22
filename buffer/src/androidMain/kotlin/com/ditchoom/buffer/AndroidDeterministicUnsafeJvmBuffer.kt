package com.ditchoom.buffer

import android.os.Parcel
import java.nio.ByteBuffer

/**
 * Android-specific concrete subclass of [DeterministicUnsafeJvmBuffer].
 * Provides Parcelable stub implementations (deterministic buffers are not parcelable).
 */
class AndroidDeterministicUnsafeJvmBuffer private constructor(
    byteBuffer: ByteBuffer,
    unsafeAddress: Long,
) : DeterministicUnsafeJvmBuffer(byteBuffer, unsafeAddress) {
    override fun describeContents(): Int = 0

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ): Unit =
        throw UnsupportedOperationException(
            "DeterministicUnsafeJvmBuffer cannot be parceled. " +
                "Use BufferFactory.shared().allocate() for parcelable buffers.",
        )

    override fun sliceImpl(byteOrder: ByteOrder): PlatformBuffer =
        AndroidDeterministicSliceBuffer(
            super.byteBuffer.slice().order(byteOrder.toJava()),
            ::isFreed,
        )

    companion object {
        fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): AndroidDeterministicUnsafeJvmBuffer {
            val address = UnsafeAllocator.allocateMemory(size.toLong())
            try {
                UnsafeMemory.setMemory(address, size.toLong(), 0)
                val byteBuffer = JniDirectByteBufferAllocator.newDirectByteBuffer(address, size)
                byteBuffer.order(byteOrder.toJava())
                return AndroidDeterministicUnsafeJvmBuffer(byteBuffer, address)
            } catch (e: Throwable) {
                UnsafeAllocator.freeMemory(address)
                throw e
            }
        }
    }
}
