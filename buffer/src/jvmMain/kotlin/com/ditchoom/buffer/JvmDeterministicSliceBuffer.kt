package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * JVM-specific concrete subclass of [DeterministicSliceBuffer].
 * On JVM, Parcelable is an empty interface so no additional methods are needed.
 */
internal class JvmDeterministicSliceBuffer(
    byteBuffer: ByteBuffer,
    isParentFreed: () -> Boolean,
) : DeterministicSliceBuffer(byteBuffer, isParentFreed) {
    override fun slice() =
        JvmDeterministicSliceBuffer(
            byteBuffer.slice().order(byteBuffer.order()),
            isParentFreed,
        )
}
