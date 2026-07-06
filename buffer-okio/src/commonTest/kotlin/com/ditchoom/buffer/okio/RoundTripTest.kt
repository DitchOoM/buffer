package com.ditchoom.buffer.okio

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.managed
import okio.Buffer
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Test group A: round-trips at boundary sizes, at non-zero position/limit (sliced buffers),
 * and across both byte orders, through every bridge entry point.
 */
class RoundTripTest {
    @Test
    fun asOkioSource_readsAllRemainingBytes() {
        for ((name, factory) in bridgeFactories) {
            for (size in ROUND_TRIP_SIZES) {
                val expected = patternBytes(size)
                val buffer = readableBufferOf(expected, factory)
                val actual = buffer.asOkioSource().buffer().readByteArray()
                assertContentEquals(expected, actual, "$name size=$size asOkioSource")
            }
        }
    }

    @Test
    fun asOkioSink_writesAllBytes() {
        for ((name, factory) in bridgeFactories) {
            for (size in ROUND_TRIP_SIZES) {
                val expected = patternBytes(size)
                val src = Buffer().apply { write(expected) }
                val dst = factory.allocate(size)
                dst.asOkioSink().write(src, src.size)
                dst.resetForRead()
                val actual = ByteArray(size) { dst.readByte() }
                assertContentEquals(expected, actual, "$name size=$size asOkioSink")
            }
        }
    }

    @Test
    fun bufferToPlatformAndBack_isIdentity() {
        for ((name, factory) in bridgeFactories) {
            for (size in ROUND_TRIP_SIZES) {
                val expected = patternBytes(size)
                val okioBuffer = Buffer().apply { write(expected) }
                val platform = okioBuffer.copyToPlatformBuffer(factory)
                val back = platform.copyToOkioBuffer()
                assertContentEquals(expected, back.readByteArray(), "$name size=$size Buffer<->Platform")
            }
        }
    }

    @Test
    fun asOkioSource_onSlicedBuffer_transfersOnlyRemaining() {
        // Non-zero position and a limit shy of capacity: only [position, limit) must transfer.
        val factory = BufferFactory.Default
        val backing = patternBytes(4096)
        val buffer = readableBufferOf(backing, factory)
        buffer.position(100)
        buffer.setLimit(3000)
        val expected = backing.copyOfRange(100, 3000)
        val actual =
            buffer
                .slice()
                .asOkioSource()
                .buffer()
                .readByteArray()
        assertContentEquals(expected, actual, "sliced asOkioSource")
    }

    @Test
    fun copyToOkioBuffer_doesNotAdvancePosition() {
        val buffer = readableBufferOf(patternBytes(64))
        buffer.position(8)
        val before = buffer.position()
        val out = buffer.copyToOkioBuffer()
        assertEquals(before, buffer.position(), "position must be unchanged")
        assertEquals((64 - 8).toLong(), out.size, "snapshot covers remaining bytes")
    }

    @Test
    fun copyToPlatformBuffer_doesNotConsumeSource() {
        val bytes = patternBytes(128)
        val okioBuffer = Buffer().apply { write(bytes) }
        val platform = okioBuffer.copyToPlatformBuffer()
        assertEquals(bytes.size.toLong(), okioBuffer.size, "source Buffer must not be consumed")
        assertContentEquals(bytes, ByteArray(bytes.size) { platform.readByte() }, "platform copy")
    }

    @Test
    fun byteOrder_preservedThroughConversion() {
        // The bridge is byte-agnostic; copyToPlatformBuffer honors the requested read order.
        val le = Buffer().apply { write(byteArrayOf(0x78, 0x56, 0x34, 0x12)) }
        val leBuf = le.copyToPlatformBuffer(BufferFactory.managed(), ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x12345678, leBuf.readInt(), "little-endian int")

        val be = Buffer().apply { write(byteArrayOf(0x12, 0x34, 0x56, 0x78)) }
        val beBuf = be.copyToPlatformBuffer(BufferFactory.managed(), ByteOrder.BIG_ENDIAN)
        assertEquals(0x12345678, beBuf.readInt(), "big-endian int")
    }

    @Test
    fun readInto_respectsByteCountLimit() {
        val src = readableBufferOf(patternBytes(1000)).asOkioSource()
        val dst = BufferFactory.Default.allocate(1000)
        val moved = src.readInto(dst, 400L)
        assertEquals(400L, moved, "readInto honors byteCount")
        dst.resetForRead()
        assertEquals(400, dst.remaining(), "dst holds exactly 400 bytes")
    }

    @Test
    fun transferTo_movesEntireSource() {
        val expected = patternBytes(5000)
        val src = readableBufferOf(expected).asOkioSource()
        val dst = BufferFactory.Default.allocate(5000)
        val moved = src.transferTo(dst)
        assertEquals(5000L, moved)
        dst.resetForRead()
        assertContentEquals(expected, ByteArray(5000) { dst.readByte() })
    }

    @Test
    fun copyToByteString_roundTripsThroughPlatformBuffer() {
        for (size in ROUND_TRIP_SIZES) {
            val expected = patternBytes(size)
            val buffer = readableBufferOf(expected)
            val byteString = buffer.copyToByteString()
            assertContentEquals(expected, byteString.toByteArray(), "size=$size copyToByteString")

            val back = byteString.copyToPlatformBuffer()
            assertContentEquals(expected, ByteArray(size) { back.readByte() }, "size=$size ByteString round-trip")
        }
    }
}
