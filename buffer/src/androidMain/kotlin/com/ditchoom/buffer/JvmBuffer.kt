package com.ditchoom.buffer

import android.os.Build
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable.Creator
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

open class JvmBuffer(
    val buffer: ByteBuffer,
) : BaseJvmBuffer(buffer) {
    override fun slice() = JvmBuffer(byteBuffer.slice())

    override fun describeContents(): Int = 0

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        if (this is ParcelableSharedMemoryBuffer) {
            dest.writeByte(1)
            return
        } else {
            dest.writeByte(0)
        }
        dest.writeInt(byteBuffer.position())
        dest.writeInt(byteBuffer.limit())
        if (byteBuffer.isDirect) {
            dest.writeByte(1)
        } else {
            dest.writeByte(0)
        }
        byteBuffer.position(0)
        byteBuffer.limit(byteBuffer.capacity())

        val (readFileDescriptor, writeFileDescriptor) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                ParcelFileDescriptor.createReliablePipe()
            } else {
                ParcelFileDescriptor.createPipe()
            }
        readFileDescriptor.writeToParcel(dest, 0)
        val scope = CoroutineScope(Dispatchers.IO + CoroutineName("IPC Write Channel Jvm Buffer"))
        scope.launch {
            FileOutputStream(writeFileDescriptor.fileDescriptor).channel.use { writeChannel ->
                writeChannel.write(buffer)
            }
            writeFileDescriptor.close()
            readFileDescriptor.close()
            scope.cancel()
        }
    }

    companion object {
        @JvmField
        val CREATOR: Creator<JvmBuffer> =
            object : Creator<JvmBuffer> {
                override fun createFromParcel(parcel: Parcel): JvmBuffer {
                    val p = parcel.dataPosition()
                    if (parcel.readByte().toInt() == 1) {
                        parcel.setDataPosition(p)
                        return ParcelableSharedMemoryBuffer.createFromParcel(parcel)
                    }
                    val position = parcel.readInt()
                    val limit = parcel.readInt()
                    val isDirect = parcel.readByte() == 1.toByte()
                    val buffer =
                        if (isDirect) {
                            ByteBuffer.allocateDirect(limit)
                        } else {
                            ByteBuffer.allocate(limit)
                        }
                    ParcelFileDescriptor.CREATOR.createFromParcel(parcel).use { pfd ->
                        FileInputStream(pfd.fileDescriptor).channel.use { readChannel -> readChannel.read(buffer) }
                    }
                    buffer.position(position)
                    buffer.limit(limit)
                    return JvmBuffer(buffer)
                }

                override fun newArray(size: Int): Array<JvmBuffer?> = arrayOfNulls(size)
            }
    }
}
