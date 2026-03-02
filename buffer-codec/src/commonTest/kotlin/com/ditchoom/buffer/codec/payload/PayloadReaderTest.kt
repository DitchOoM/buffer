package com.ditchoom.buffer.codec.payload

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.pool.BufferPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PayloadReaderTest {
    @Test
    fun readByteWithinActiveReader() {
        val buf = PlatformBuffer.allocate(4)
        buf.writeByte(0x42)
        buf.writeShort(1000)
        buf.writeByte(0x7F)
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        assertEquals(0x42.toByte(), reader.readByte())
    }

    @Test
    fun readShort() {
        val buf = PlatformBuffer.allocate(2)
        buf.writeShort(0x1234.toShort())
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        assertEquals(0x1234.toShort(), reader.readShort())
    }

    @Test
    fun readInt() {
        val buf = PlatformBuffer.allocate(4)
        buf.writeInt(0x12345678)
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        assertEquals(0x12345678, reader.readInt())
    }

    @Test
    fun readLong() {
        val buf = PlatformBuffer.allocate(8)
        buf.writeLong(0x123456789ABCDEF0L)
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        assertEquals(0x123456789ABCDEF0L, reader.readLong())
    }

    @Test
    fun readFloat() {
        val buf = PlatformBuffer.allocate(4)
        buf.writeFloat(3.14f)
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        val result = reader.readFloat()
        // Compare bits to avoid JS float precision issue (3.14f toString differs on JS)
        assertEquals(3.14f.toBits(), result.toBits())
    }

    @Test
    fun readDouble() {
        val buf = PlatformBuffer.allocate(8)
        buf.writeDouble(2.718281828)
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        assertEquals(2.718281828, reader.readDouble())
    }

    @Test
    fun readString() {
        val text = "Hello"
        val buf = PlatformBuffer.allocate(64)
        buf.writeString(text, com.ditchoom.buffer.Charset.UTF8)
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        assertEquals(text, reader.readString(5))
    }

    @Test
    fun remaining() {
        val buf = PlatformBuffer.allocate(8)
        buf.writeInt(1)
        buf.writeInt(2)
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        assertEquals(8, reader.remaining())
        reader.readInt()
        assertEquals(4, reader.remaining())
    }

    @Test
    fun copyToBufferWithZone() {
        val buf = PlatformBuffer.allocate(4)
        buf.writeInt(0x12345678)
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        val copy = reader.copyToBuffer(AllocationZone.Heap)

        // The copy should be an independent buffer with the same data
        assertEquals(4, copy.remaining())
        assertEquals(0x12345678, copy.readInt())

        // Original reader should still have data (copyToBuffer uses slice, not consume)
        assertEquals(4, reader.remaining())
    }

    @Test
    fun copyToBufferWithPool() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val buf = PlatformBuffer.allocate(4)
        buf.writeInt(0xDEADBEEF.toInt())
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        val copy = reader.copyToBuffer(pool)

        assertEquals(4, copy.remaining())
        assertEquals(0xDEADBEEF.toInt(), copy.readInt())
    }

    @Test
    fun transferToBuffer() {
        val buf = PlatformBuffer.allocate(4)
        buf.writeInt(0x12345678)
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)

        val destBuf = PlatformBuffer.allocate(4)
        reader.transferTo(destBuf)

        destBuf.resetForRead()
        assertEquals(0x12345678, destBuf.readInt())
    }

    @Test
    fun transferToBufferMultipleBytes() {
        val buf = PlatformBuffer.allocate(8)
        buf.writeByte(0x11)
        buf.writeByte(0x22)
        buf.writeByte(0x33)
        buf.writeByte(0x44)
        buf.writeByte(0x55)
        buf.writeByte(0x66)
        buf.writeByte(0x77)
        buf.writeByte(0x88.toByte())
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        val destBuf = PlatformBuffer.allocate(8)
        reader.transferTo(destBuf)

        destBuf.resetForRead()
        assertEquals(0x11.toByte(), destBuf.readByte())
        assertEquals(0x22.toByte(), destBuf.readByte())
        assertEquals(0x33.toByte(), destBuf.readByte())
        assertEquals(0x44.toByte(), destBuf.readByte())
        assertEquals(0x55.toByte(), destBuf.readByte())
        assertEquals(0x66.toByte(), destBuf.readByte())
        assertEquals(0x77.toByte(), destBuf.readByte())
        assertEquals(0x88.toByte(), destBuf.readByte())
    }

    @Test
    fun readByteAfterReleaseThrows() {
        val buf = PlatformBuffer.allocate(1)
        buf.writeByte(0x42)
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        reader.release()
        assertFailsWith<IllegalStateException> { reader.readByte() }
    }

    @Test
    fun readShortAfterReleaseThrows() {
        val buf = PlatformBuffer.allocate(2)
        buf.writeShort(1)
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        reader.release()
        assertFailsWith<IllegalStateException> { reader.readShort() }
    }

    @Test
    fun readIntAfterReleaseThrows() {
        val buf = PlatformBuffer.allocate(4)
        buf.writeInt(1)
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        reader.release()
        assertFailsWith<IllegalStateException> { reader.readInt() }
    }

    @Test
    fun readLongAfterReleaseThrows() {
        val buf = PlatformBuffer.allocate(8)
        buf.writeLong(1L)
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        reader.release()
        assertFailsWith<IllegalStateException> { reader.readLong() }
    }

    @Test
    fun readFloatAfterReleaseThrows() {
        val buf = PlatformBuffer.allocate(4)
        buf.writeFloat(1.0f)
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        reader.release()
        assertFailsWith<IllegalStateException> { reader.readFloat() }
    }

    @Test
    fun readDoubleAfterReleaseThrows() {
        val buf = PlatformBuffer.allocate(8)
        buf.writeDouble(1.0)
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        reader.release()
        assertFailsWith<IllegalStateException> { reader.readDouble() }
    }

    @Test
    fun readStringAfterReleaseThrows() {
        val buf = PlatformBuffer.allocate(8)
        buf.writeString("Hi", com.ditchoom.buffer.Charset.UTF8)
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        reader.release()
        assertFailsWith<IllegalStateException> { reader.readString(2) }
    }

    @Test
    fun remainingAfterReleaseThrows() {
        val buf = PlatformBuffer.allocate(4)
        buf.writeInt(1)
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        reader.release()
        assertFailsWith<IllegalStateException> { reader.remaining() }
    }

    @Test
    fun copyToBufferAfterReleaseThrows() {
        val buf = PlatformBuffer.allocate(4)
        buf.writeInt(1)
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        reader.release()
        assertFailsWith<IllegalStateException> { reader.copyToBuffer() }
    }

    @Test
    fun copyToBufferPoolAfterReleaseThrows() {
        val pool = BufferPool(defaultBufferSize = 1024)
        val buf = PlatformBuffer.allocate(4)
        buf.writeInt(1)
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        reader.release()
        assertFailsWith<IllegalStateException> { reader.copyToBuffer(pool) }
    }

    @Test
    fun transferToAfterReleaseThrows() {
        val buf = PlatformBuffer.allocate(4)
        buf.writeInt(1)
        buf.resetForRead()

        val reader = ReadBufferPayloadReader(buf)
        reader.release()
        val destBuf = PlatformBuffer.allocate(4)
        assertFailsWith<IllegalStateException> { reader.transferTo(destBuf) }
    }
}
