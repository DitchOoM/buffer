package com.ditchoom.buffer

import android.os.Build
import android.os.Parcel
import android.os.SharedMemory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@SmallTest
class ParcelableTests {
    @Test
    fun parcelSharedMemory() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        val sharedMemoryBuffer = SharedMemory.create("test", 10_000_000)
        val s = ParcelableSharedMemoryBuffer(sharedMemoryBuffer.mapReadWrite(), sharedMemoryBuffer)
        val i = Random.nextInt()
        s.writeInt(i)

        val parcel = Parcel.obtain()
        s.writeToParcel(parcel, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
        parcel.setDataPosition(0)

        val createdFromParcel = ParcelableSharedMemoryBuffer.CREATOR.createFromParcel(parcel)
        createdFromParcel.resetForRead()
        assertEquals(i, createdFromParcel.readInt())
    }

    @Test
    fun parcelFileDescriptor() {
        val buffer = ByteBuffer.allocateDirect(10_000_000)
        val s = JvmBuffer(buffer)
        val i = Random.nextInt()
        s.writeInt(i)

        val parcel = Parcel.obtain()
        s.writeToParcel(parcel, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
        parcel.setDataPosition(0)

        val createdFromParcel = JvmBuffer.CREATOR.createFromParcel(parcel)
        createdFromParcel.resetForRead()
        assertEquals(i, createdFromParcel.readInt())
    }

    @Test
    fun copyByteArray() {
        val s = JvmBuffer(ByteBuffer.allocate(1_000_000))
        val i = Random.nextInt()
        s.writeInt(i)

        val parcel = Parcel.obtain()
        s.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)

        val createdFromParcel = JvmBuffer.CREATOR.createFromParcel(parcel)
        createdFromParcel.resetForRead()
        assertEquals(i, createdFromParcel.readInt())
    }
}
