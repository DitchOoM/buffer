package com.ditchoom.buffer

import android.os.Parcel
import java.nio.ByteBuffer

/**
 * Android-specific subclass that provides Parcelable stub implementations.
 * Deterministic buffers are not parcelable — they use explicit memory management.
 */
class AndroidDeterministicDirectJvmBuffer(
    byteBuffer: ByteBuffer,
) : DeterministicDirectJvmBuffer(byteBuffer) {
    override fun describeContents(): Int = 0

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ): Unit =
        throw UnsupportedOperationException(
            "DeterministicDirectJvmBuffer cannot be parceled. " +
                "Use BufferFactory.shared().allocate() for parcelable buffers.",
        )

    override fun slice(): PlatformBuffer =
        AndroidDeterministicSliceBuffer(
            super.byteBuffer.slice().order(super.byteBuffer.order()),
            ::isFreed,
        )
}
