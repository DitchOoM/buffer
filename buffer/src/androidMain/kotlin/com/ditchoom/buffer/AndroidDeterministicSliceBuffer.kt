package com.ditchoom.buffer

import android.os.Parcel
import java.nio.ByteBuffer

/**
 * Android-specific concrete subclass of [DeterministicSliceBuffer].
 * Provides Parcelable stub implementations (slices are not parcelable).
 */
class AndroidDeterministicSliceBuffer(
    byteBuffer: ByteBuffer,
    isParentFreed: () -> Boolean,
) : DeterministicSliceBuffer(byteBuffer, isParentFreed) {
    override fun describeContents(): Int = 0

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ): Unit =
        throw UnsupportedOperationException(
            "DeterministicSliceBuffer cannot be parceled. " +
                "Use BufferFactory.shared().allocate() for parcelable buffers.",
        )

    override fun slice() =
        AndroidDeterministicSliceBuffer(
            byteBuffer.slice().order(byteBuffer.order()),
            parentFreedCheck!!,
        )
}
