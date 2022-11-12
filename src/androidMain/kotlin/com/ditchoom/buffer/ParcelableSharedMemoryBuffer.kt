package com.ditchoom.buffer

import android.annotation.TargetApi
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.os.SharedMemory
import java.nio.ByteBuffer

@TargetApi(Build.VERSION_CODES.O_MR1)
class ParcelableSharedMemoryBuffer(buffer: ByteBuffer, private val sharedMemory: SharedMemory) : JvmBuffer(buffer) {
    override fun describeContents(): Int = Parcelable.CONTENTS_FILE_DESCRIPTOR

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeParcelable(sharedMemory, 0)
        dest.writeInt(buffer.position())
        dest.writeInt(buffer.limit())
    }

    override suspend fun close() {
        super.close()
        sharedMemory.close()
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ParcelableSharedMemoryBuffer> =
            object : Parcelable.Creator<ParcelableSharedMemoryBuffer> {
                override fun createFromParcel(parcel: Parcel): ParcelableSharedMemoryBuffer {
                    val sharedMemory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        parcel.readParcelable(javaClass.classLoader, SharedMemory::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        parcel.readParcelable(javaClass.classLoader)
                    }!!
                    val buffer = ParcelableSharedMemoryBuffer(sharedMemory.mapReadWrite(), sharedMemory)
                    buffer.position(parcel.readInt())
                    buffer.setLimit(parcel.readInt())
                    return buffer
                }

                override fun newArray(size: Int): Array<ParcelableSharedMemoryBuffer?> {
                    return arrayOfNulls(size)
                }
            }
    }

}