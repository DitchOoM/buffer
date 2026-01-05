package com.ditchoom.buffer

import android.annotation.TargetApi
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.os.SharedMemory
import java.nio.ByteBuffer

@TargetApi(Build.VERSION_CODES.O_MR1)
class ParcelableSharedMemoryBuffer(
    buffer: ByteBuffer,
    private val sharedMemory: SharedMemory,
) : JvmBuffer(buffer),
    Parcelable,
    SharedMemoryAccess {
    /**
     * Always returns true since this buffer is backed by SharedMemory.
     */
    override val isShared: Boolean get() = true
    override fun describeContents(): Int = Parcelable.CONTENTS_FILE_DESCRIPTOR

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        super.writeToParcel(dest, flags)
        dest.writeParcelable(sharedMemory, flags)
        dest.writeInt(buffer.position())
        dest.writeInt(buffer.limit())
    }

    override suspend fun close() {
        super.close()
        sharedMemory.close()
    }

    companion object CREATOR : Parcelable.Creator<ParcelableSharedMemoryBuffer> {
        override fun createFromParcel(parcel: Parcel): ParcelableSharedMemoryBuffer {
            parcel.readByte() // ignore this first byte
            val sharedMemory =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    parcel.readParcelable(this::class.java.classLoader, SharedMemory::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    parcel.readParcelable(this::class.java.classLoader)
                }!!
            val buffer =
                ParcelableSharedMemoryBuffer(sharedMemory.mapReadWrite(), sharedMemory)
            buffer.position(parcel.readInt())
            buffer.setLimit(parcel.readInt())
            return buffer
        }

        override fun newArray(size: Int): Array<ParcelableSharedMemoryBuffer?> = arrayOfNulls(size)
    }
}
