package com.ditchoom.buffer

import android.os.Build
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable.Creator
import android.os.SharedMemory
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
        val savedPosition = byteBuffer.position()
        val savedLimit = byteBuffer.limit()
        val capacity = byteBuffer.capacity()
        dest.writeInt(savedPosition)
        dest.writeInt(savedLimit)
        dest.writeInt(capacity)
        if (byteBuffer.isDirect) {
            dest.writeByte(1)
        } else {
            dest.writeByte(0)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && capacity > 0) {
            // API 27+: SharedMemory — zero-copy across processes, no race conditions
            dest.writeByte(1)
            byteBuffer.position(0)
            byteBuffer.limit(capacity)
            val sharedMem = SharedMemory.create(null, capacity)
            val mapped = sharedMem.mapReadWrite()
            mapped.put(byteBuffer)
            SharedMemory.unmap(mapped)
            sharedMem.writeToParcel(dest, flags)
            sharedMem.close()
            // Restore original state
            byteBuffer.limit(savedLimit)
            byteBuffer.position(savedPosition)
        } else if (capacity > 0) {
            // API < 27: pipe fallback
            dest.writeByte(0)
            byteBuffer.position(0)
            byteBuffer.limit(capacity)
            val (readFd, writeFd) =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    ParcelFileDescriptor.createReliablePipe()
                } else {
                    ParcelFileDescriptor.createPipe()
                }
            readFd.writeToParcel(dest, 0)
            Thread {
                try {
                    FileOutputStream(writeFd.fileDescriptor).channel.use { it.write(buffer) }
                } finally {
                    writeFd.close()
                    readFd.close()
                }
            }.start()
        } else {
            // Empty buffer — no data to transfer
            dest.writeByte(0)
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
                    val capacity = parcel.readInt()
                    val isDirect = parcel.readByte() == 1.toByte()
                    val useSharedMem = parcel.readByte() == 1.toByte()

                    val buffer =
                        if (useSharedMem && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            // API 27+: read SharedMemory, copy into correct buffer type
                            val sharedMem = SharedMemory.CREATOR.createFromParcel(parcel)
                            val mapped = sharedMem.mapReadWrite()
                            val buf =
                                if (isDirect) {
                                    ByteBuffer.allocateDirect(capacity)
                                } else {
                                    ByteBuffer.allocate(capacity)
                                }
                            mapped.limit(capacity)
                            buf.put(mapped)
                            SharedMemory.unmap(mapped)
                            sharedMem.close()
                            buf
                        } else if (!useSharedMem && capacity > 0) {
                            // API < 27: read from pipe with loop
                            val buf =
                                if (isDirect) {
                                    ByteBuffer.allocateDirect(capacity)
                                } else {
                                    ByteBuffer.allocate(capacity)
                                }
                            ParcelFileDescriptor.CREATOR.createFromParcel(parcel).use { pfd ->
                                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                                    while (buf.hasRemaining()) {
                                        if (ch.read(buf) == -1) break
                                    }
                                }
                            }
                            buf
                        } else {
                            // Empty buffer
                            if (isDirect) ByteBuffer.allocateDirect(capacity) else ByteBuffer.allocate(capacity)
                        }
                    // Restore the exact buffer state from before writeToParcel
                    buffer.limit(limit)
                    buffer.position(position)
                    return JvmBuffer(buffer)
                }

                override fun newArray(size: Int): Array<JvmBuffer?> = arrayOfNulls(size)
            }
    }
}
