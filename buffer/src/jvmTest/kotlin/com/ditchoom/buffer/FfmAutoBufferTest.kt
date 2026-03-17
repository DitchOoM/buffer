package com.ditchoom.buffer

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for FfmAutoBuffer — GC-managed FFM buffer returned by BufferFactory.Default on JVM 21+.
 */
class FfmAutoBufferTest {
    private fun createAutoBuffer(
        size: Int,
        byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    ): FfmAutoBuffer {
        val arena = Arena.ofAuto()
        val segment = arena.allocate(size.toLong())
        val javaByteOrder = byteOrder.toJava()
        val globalView =
            MemorySegment
                .ofAddress(segment.address())
                .reinterpret(size.toLong())
        val byteBuffer = globalView.asByteBuffer().order(javaByteOrder)
        return FfmAutoBuffer(segment, byteBuffer)
    }

    @Test
    fun `FfmAutoBuffer is NOT CloseableBuffer`() {
        val buffer = createAutoBuffer(64)
        assertFalse(buffer is CloseableBuffer)
    }

    @Test
    fun `FfmAutoBuffer implements NativeMemoryAccess`() {
        val buffer = createAutoBuffer(64)
        assertIs<NativeMemoryAccess>(buffer)
    }

    @Test
    fun `freeNativeMemory is a no-op`() {
        val buffer = createAutoBuffer(64)
        buffer.writeInt(42)
        buffer.freeNativeMemory() // should not throw
        buffer.resetForRead()
        assertEquals(42, buffer.readInt()) // buffer still usable
    }

    @Test
    fun `nativeAddress returns valid non-zero address`() {
        val buffer = createAutoBuffer(1024)
        assertNotEquals(0L, buffer.nativeAddress)
    }

    @Test
    fun `nativeSize matches allocated size`() {
        val buffer = createAutoBuffer(512)
        assertEquals(512L, buffer.nativeSize)
    }

    @Test
    fun `slice returns FfmSliceBuffer`() {
        val buffer = createAutoBuffer(64)
        buffer.writeInt(0x12345678)
        buffer.resetForRead()
        val slice = buffer.slice()
        assertIs<FfmSliceBuffer>(slice)
        assertEquals(0x12345678, slice.readInt())
    }

    @Test
    fun `basic read write operations work`() {
        val buffer = createAutoBuffer(64)
        buffer.writeInt(0x12345678)
        buffer.writeLong(0x123456789ABCDEF0L)
        buffer.writeShort(0x1234.toShort())
        buffer.writeByte(0x42)
        buffer.resetForRead()
        assertEquals(0x12345678, buffer.readInt())
        assertEquals(0x123456789ABCDEF0L, buffer.readLong())
        assertEquals(0x1234.toShort(), buffer.readShort())
        assertEquals(0x42.toByte(), buffer.readByte())
    }

    @Test
    fun `Default factory returns FfmAutoBuffer on JVM 21+`() {
        val buffer = BufferFactory.Default.allocate(64)
        // On JVM 21+ (multi-release JAR), Default should return FfmAutoBuffer
        // On JVM < 21, this returns DirectJvmBuffer — test passes either way
        if (buffer is FfmAutoBuffer) {
            assertFalse(buffer is CloseableBuffer)
            assertTrue(buffer.nativeAddress != 0L)
        }
    }

    @Test
    fun `byte order is respected`() {
        val bufferBE = createAutoBuffer(8, ByteOrder.BIG_ENDIAN)
        val bufferLE = createAutoBuffer(8, ByteOrder.LITTLE_ENDIAN)
        bufferBE.writeInt(0x01020304)
        bufferLE.writeInt(0x01020304)
        bufferBE.resetForRead()
        bufferLE.resetForRead()
        val beByte = bufferBE.readByte()
        bufferLE.position(0)
        val leByte = bufferLE.readByte()
        assertNotEquals(beByte, leByte, "Byte order should affect raw byte layout")
    }
}
