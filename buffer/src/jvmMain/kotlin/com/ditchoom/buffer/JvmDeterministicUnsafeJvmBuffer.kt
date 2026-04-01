package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * JVM-specific concrete subclass of [DeterministicUnsafeJvmBuffer].
 * On JVM, Parcelable is an empty interface so no additional methods are needed.
 */
internal class JvmDeterministicUnsafeJvmBuffer(
    byteBuffer: ByteBuffer,
    unsafeAddress: Long,
) : DeterministicUnsafeJvmBuffer(byteBuffer, unsafeAddress) {
    override fun sliceImpl(): PlatformBuffer =
        JvmDeterministicSliceBuffer(
            super.byteBuffer.slice().order(super.byteBuffer.order()),
            unsafeAddress,
            ::isFreed,
        )

    companion object {
        fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): JvmDeterministicUnsafeJvmBuffer {
            val address = UnsafeAllocator.allocateMemory(size.toLong())
            UnsafeMemory.setMemory(address, size.toLong(), 0)
            val byteBuffer =
                UnsafeMemory.tryWrapAsDirectByteBuffer(address, size)
                    ?: throw UnsupportedOperationException(
                        "Cannot create DeterministicUnsafeJvmBuffer: " +
                            "DirectByteBuffer reflection is not available.",
                    )
            byteBuffer.order(byteOrder.toJava())
            return JvmDeterministicUnsafeJvmBuffer(byteBuffer, address)
        }
    }
}
