package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * JVM-specific concrete subclass of [DeterministicDirectJvmBuffer].
 * On JVM, Parcelable is an empty interface so no additional methods are needed.
 */
internal class JvmDeterministicDirectJvmBuffer(
    byteBuffer: ByteBuffer,
) : DeterministicDirectJvmBuffer(byteBuffer) {
    override fun slice() = JvmDeterministicDirectJvmBuffer(byteBuffer.slice().order(byteBuffer.order()))
}
