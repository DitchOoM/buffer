package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * JVM-specific concrete subclass of [DeterministicDirectJvmBuffer].
 * On JVM, Parcelable is an empty interface so no additional methods are needed.
 */
internal class JvmDeterministicDirectJvmBuffer(
    byteBuffer: ByteBuffer,
) : DeterministicDirectJvmBuffer(byteBuffer) {
    override fun slice(): PlatformBuffer =
        JvmDeterministicSliceBuffer(
            super.byteBuffer.slice().order(super.byteBuffer.order()),
            directAddress,
            ::isFreed,
        )
}
