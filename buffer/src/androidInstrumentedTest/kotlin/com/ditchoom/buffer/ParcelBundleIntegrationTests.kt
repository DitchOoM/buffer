package com.ditchoom.buffer

import android.os.Build
import android.os.Bundle
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Integration tests for Parcelable buffer round-trips through Parcel and Bundle.
 *
 * Validates content integrity across different buffer sizes including
 * buffers that exceed Android's 1MB Binder transaction limit (which forces
 * the ParcelFileDescriptor pipe path for direct buffers).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ParcelBundleIntegrationTests {
    // --- Parcel round-trip with content validation ---

    @Test
    fun directBufferSmallParcelRoundTrip() {
        parcelRoundTrip(BufferFactory.Default.allocate(64), 64)
    }

    @Test
    fun directBuffer1KBParcelRoundTrip() {
        parcelRoundTrip(BufferFactory.Default.allocate(1024), 1024)
    }

    @Test
    fun directBuffer1MBParcelRoundTrip() {
        parcelRoundTrip(BufferFactory.Default.allocate(1_000_000), 1_000_000)
    }

    @Test
    fun directBuffer4MBParcelRoundTrip() {
        parcelRoundTrip(BufferFactory.Default.allocate(4_000_000), 4_000_000)
    }

    @Test
    fun directBuffer25MBParcelRoundTrip() {
        parcelRoundTrip(BufferFactory.Default.allocate(25_000_000), 25_000_000)
    }

    @Test
    fun heapBufferSmallParcelRoundTrip() {
        parcelRoundTrip(BufferFactory.managed().allocate(64), 64)
    }

    @Test
    fun heapBuffer1MBParcelRoundTrip() {
        parcelRoundTrip(BufferFactory.managed().allocate(1_000_000), 1_000_000)
    }

    @Test
    fun heapBuffer25MBParcelRoundTrip() {
        parcelRoundTrip(BufferFactory.managed().allocate(25_000_000), 25_000_000)
    }

    @Test
    fun sharedMemorySmallParcelRoundTrip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        parcelRoundTrip(BufferFactory.shared().allocate(64), 64)
    }

    @Test
    fun sharedMemory1MBParcelRoundTrip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        parcelRoundTrip(BufferFactory.shared().allocate(1_000_000), 1_000_000)
    }

    @Test
    fun sharedMemory25MBParcelRoundTrip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        parcelRoundTrip(BufferFactory.shared().allocate(25_000_000), 25_000_000)
    }

    // --- Bundle save/restore ---

    @Test
    fun directBufferBundleSaveRestore() {
        bundleSaveRestore(BufferFactory.Default.allocate(4096), 4096)
    }

    @Test
    fun directBufferLargeBundleSaveRestore() {
        bundleSaveRestore(BufferFactory.Default.allocate(4_000_000), 4_000_000)
    }

    @Test
    fun heapBufferBundleSaveRestore() {
        bundleSaveRestore(BufferFactory.managed().allocate(4096), 4096)
    }

    @Test
    fun sharedMemoryBundleSaveRestore() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        bundleSaveRestore(BufferFactory.shared().allocate(4096), 4096)
    }

    // --- Position/limit preservation ---

    @Test
    fun positionAndLimitPreservedAfterParcel() {
        val buf = BufferFactory.Default.allocate(100) as JvmBuffer
        // Write 40 bytes of data
        val data = Random.nextBytes(40)
        buf.writeBytes(data)
        // Set position to 10, limit stays at 40 (write position)
        buf.position(10)
        buf.setLimit(40)

        val parcel = Parcel.obtain()
        try {
            buf.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = JvmBuffer.CREATOR.createFromParcel(parcel)
            assertEquals(10, restored.position())
            assertEquals(40, restored.limit())

            // Read from position 10 to limit — should match original data[10..40]
            val expected = data.copyOfRange(10, 40)
            val actual = ByteArray(30)
            for (i in actual.indices) actual[i] = restored.readByte()
            assertContentEquals(expected, actual)
        } finally {
            parcel.recycle()
        }
    }

    // --- Mixed types round-trip ---

    @Test
    fun mixedTypesPreservedThroughParcel() {
        val buf = BufferFactory.Default.allocate(23) as JvmBuffer
        buf.writeByte(0x42)
        buf.writeShort(0x1234.toShort())
        buf.writeInt(0xDEADBEEF.toInt())
        buf.writeLong(0x0102030405060708L)
        buf.writeFloat(3.14f)
        buf.writeDouble(2.718281828)
        buf.resetForRead()

        val parcel = Parcel.obtain()
        try {
            buf.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = JvmBuffer.CREATOR.createFromParcel(parcel)
            restored.resetForRead()

            assertEquals(0x42.toByte(), restored.readByte())
            assertEquals(0x1234.toShort(), restored.readShort())
            assertEquals(0xDEADBEEF.toInt(), restored.readInt())
            assertEquals(0x0102030405060708L, restored.readLong())
            assertEquals(3.14f, restored.readFloat())
            assertEquals(2.718281828, restored.readDouble())
        } finally {
            parcel.recycle()
        }
    }

    // --- Empty buffer ---

    @Test
    fun emptyBufferParcelRoundTrip() {
        val buf = BufferFactory.Default.allocate(0) as JvmBuffer
        val parcel = Parcel.obtain()
        try {
            buf.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = JvmBuffer.CREATOR.createFromParcel(parcel)
            assertEquals(0, restored.remaining())
        } finally {
            parcel.recycle()
        }
    }

    // --- Helpers ---

    private fun parcelRoundTrip(
        buffer: PlatformBuffer,
        size: Int,
    ) {
        val jvmBuffer = buffer as JvmBuffer
        val data = Random.nextBytes(size)
        jvmBuffer.writeBytes(data)
        jvmBuffer.resetForRead()

        val parcel = Parcel.obtain()
        try {
            jvmBuffer.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)

            val restored = JvmBuffer.CREATOR.createFromParcel(parcel)
            restored.resetForRead()
            assertEquals(size, restored.remaining(), "Size mismatch for $size-byte buffer")

            val readBack = ByteArray(size)
            for (i in readBack.indices) readBack[i] = restored.readByte()
            assertContentEquals(data, readBack, "Content mismatch for $size-byte buffer")
        } finally {
            parcel.recycle()
        }
    }

    private fun bundleSaveRestore(
        buffer: PlatformBuffer,
        size: Int,
    ) {
        val jvmBuffer = buffer as JvmBuffer
        val data = Random.nextBytes(size)
        jvmBuffer.writeBytes(data)
        jvmBuffer.resetForRead()

        // Save to bundle
        val bundle = Bundle()
        bundle.putParcelable("buffer", jvmBuffer)

        // Serialize bundle to parcel and back (simulates savedInstanceState)
        val parcel = Parcel.obtain()
        try {
            bundle.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restoredBundle = Bundle()
            restoredBundle.readFromParcel(parcel)
            restoredBundle.classLoader = JvmBuffer::class.java.classLoader

            @Suppress("DEPRECATION")
            val restored = restoredBundle.getParcelable<JvmBuffer>("buffer")!!
            restored.resetForRead()
            assertEquals(size, restored.remaining(), "Bundle size mismatch for $size-byte buffer")

            val readBack = ByteArray(size)
            for (i in readBack.indices) readBack[i] = restored.readByte()
            assertContentEquals(data, readBack, "Bundle content mismatch for $size-byte buffer")
        } finally {
            parcel.recycle()
        }
    }
}
