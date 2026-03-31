package com.ditchoom.buffer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Android emulator tests for deterministic buffers.
 * Validates that Unsafe.allocateMemory + DirectByteBuffer wrapping works on real Android.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class DeterministicBufferAndroidTest {
    @Test
    fun deterministicAllocateAndFree() {
        val buffer = BufferFactory.deterministic().allocate(64, ByteOrder.BIG_ENDIAN)
        assertTrue(buffer is CloseableBuffer)
        assertTrue(buffer is NativeMemoryAccess)
        buffer.writeInt(0xDEADBEEF.toInt())
        buffer.resetForRead()
        assertEquals(0xDEADBEEF.toInt(), buffer.readInt())
        buffer.freeNativeMemory()
        assertTrue((buffer as CloseableBuffer).isFreed)
    }

    @Test
    fun deterministicRoundTripAllTypes() {
        BufferFactory.deterministic().allocate(128, ByteOrder.BIG_ENDIAN).use { buffer ->
            buffer.writeByte(0x42)
            buffer.writeShort(1000)
            buffer.writeInt(123456)
            buffer.writeLong(9876543210L)
            buffer.writeFloat(3.14f)
            buffer.writeDouble(2.718281828)
            buffer.resetForRead()
            assertEquals(0x42.toByte(), buffer.readByte())
            assertEquals(1000.toShort(), buffer.readShort())
            assertEquals(123456, buffer.readInt())
            assertEquals(9876543210L, buffer.readLong())
            assertEquals(3.14f, buffer.readFloat())
            assertEquals(2.718281828, buffer.readDouble())
        }
    }

    @Test
    fun deterministicLittleEndian() {
        BufferFactory.deterministic().allocate(64, ByteOrder.LITTLE_ENDIAN).use { buffer ->
            buffer.writeInt(0x01020304)
            buffer.resetForRead()
            assertEquals(0x04.toByte(), buffer[0])
            assertEquals(0x03.toByte(), buffer[1])
            assertEquals(0x01020304, buffer.readInt())
        }
    }

    @Test
    fun deterministicNativeAddress() {
        BufferFactory.deterministic().allocate(64, ByteOrder.BIG_ENDIAN).use { buffer ->
            val nma = buffer.nativeMemoryAccess
            assertTrue(nma != null)
            assertTrue(nma!!.nativeAddress != 0L)
            assertTrue(nma.nativeSize >= 64L)
        }
    }

    @Test
    fun deterministicFreeIsIdempotent() {
        val buffer = BufferFactory.deterministic().allocate(64, ByteOrder.BIG_ENDIAN)
        buffer.freeNativeMemory()
        buffer.freeNativeMemory() // second call should not crash
        assertTrue((buffer as CloseableBuffer).isFreed)
    }

    @Test
    fun deterministicThrowsAfterFree() {
        val buffer = BufferFactory.deterministic().allocate(64, ByteOrder.BIG_ENDIAN)
        buffer.freeNativeMemory()
        assertFailsWith<IllegalStateException> {
            buffer.readByte()
        }
    }

    @Test
    fun deterministicStringRoundTrip() {
        BufferFactory.deterministic().allocate(256, ByteOrder.BIG_ENDIAN).use { buffer ->
            val text = "Hello from Android deterministic buffer!"
            buffer.writeString(text)
            buffer.resetForRead()
            assertEquals(text, buffer.readString(text.encodeToByteArray().size))
        }
    }

    @Test
    fun deterministicCopyToHeap() {
        BufferFactory.deterministic().allocate(64, ByteOrder.BIG_ENDIAN).use { det ->
            det.writeInt(0xCAFEBABE.toInt())
            det.resetForRead()

            val heap = BufferFactory.managed().allocate(64, ByteOrder.BIG_ENDIAN)
            heap.write(det)
            heap.resetForRead()
            assertEquals(0xCAFEBABE.toInt(), heap.readInt())
        }
    }
}
