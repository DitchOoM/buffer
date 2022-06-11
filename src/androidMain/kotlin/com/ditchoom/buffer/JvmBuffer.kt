package com.ditchoom.buffer

import android.os.Parcel
import android.os.Parcelable
import java.nio.ByteBuffer

/**
 * Memory copy based parcelable conforming buffer.
 */
open class JvmBuffer(val buffer: ByteBuffer) : BaseJvmBuffer(buffer) {
    override fun describeContents(): Int = Parcelable.CONTENTS_FILE_DESCRIPTOR

    override fun writeToParcel(dest: Parcel, flags: Int) {
        val position = buffer.position()
        buffer.position(0)
        val array = buffer.toArray()
        dest.writeInt(array.size)
        dest.writeByteArray(array)
        dest.writeInt(position)
        dest.writeInt(buffer.limit())
    }

    companion object {
        val CREATOR: Parcelable.Creator<JvmBuffer> = object : Parcelable.Creator<JvmBuffer> {
            override fun createFromParcel(parcel: Parcel): JvmBuffer {
                val byteArray = ByteArray(parcel.readInt())
                parcel.readByteArray(byteArray)
                val buffer = JvmBuffer(ByteBuffer.wrap(byteArray))
                buffer.position(parcel.readInt())
                buffer.setLimit(parcel.readInt())
                return buffer
            }

            override fun newArray(size: Int): Array<JvmBuffer?> {
                return arrayOfNulls(size)
            }
        }
    }

}