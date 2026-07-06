package com.ditchoom.buffer.kotlinxio

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.managed
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Test group A: round-trips at boundary sizes, at non-zero position/limit (sliced buffers),
 * and across both byte orders, through every bridge entry point.
 */
class RoundTripTest {
    @Test
    fun asRawSource_readsAllRemainingBytes() {
        for ((name, factory) in bridgeFactories) {
            for (size in ROUND_TRIP_SIZES) {
                val expected = patternBytes(size)
                val buffer = readableBufferOf(expected, factory)
                val actual = buffer.asRawSource().buffered().readByteArray()
                assertContentEquals(expected, actual, "$name size=$size asRawSource")
            }
        }
    }

    @Test
    fun asRawSink_writesAllBytes() {
        for ((name, factory) in bridgeFactories) {
            for (size in ROUND_TRIP_SIZES) {
                val expected = patternBytes(size)
                val src = Buffer().apply { write(expected) }
                val dst = factory.allocate(size)
                dst.asRawSink().write(src, src.size)
                dst.resetForRead()
                val actual = ByteArray(size) { dst.readByte() }
                assertContentEquals(expected, actual, "$name size=$size asRawSink")
            }
        }
    }

    @Test
    fun bufferToPlatformAndBack_isIdentity() {
        for ((name, factory) in bridgeFactories) {
            for (size in ROUND_TRIP_SIZES) {
                val expected = patternBytes(size)
                val kx = Buffer().apply { write(expected) }
                val platform = kx.copyToPlatformBuffer(factory)
                val back = platform.copyToKotlinxIoBuffer()
                assertContentEquals(expected, back.readByteArray(), "$name size=$size Buffer<->Platform")
            }
        }
    }

    @Test
    fun asRawSource_onSlicedBuffer_transfersOnlyRemaining() {
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
                .asRawSource()
                .buffered()
                .readByteArray()
        assertContentEquals(expected, actual, "sliced asRawSource")
    }

    @Test
    fun copyToKotlinxIoBuffer_doesNotAdvancePosition() {
        val buffer = readableBufferOf(patternBytes(64))
        buffer.position(8)
        val before = buffer.position()
        val out = buffer.copyToKotlinxIoBuffer()
        assertEquals(before, buffer.position(), "position must be unchanged")
        assertEquals((64 - 8).toLong(), out.size, "snapshot covers remaining bytes")
    }

    @Test
    fun copyToPlatformBuffer_doesNotConsumeSource() {
        val bytes = patternBytes(128)
        val kx = Buffer().apply { write(bytes) }
        val platform = kx.copyToPlatformBuffer()
        assertEquals(bytes.size.toLong(), kx.size, "source Buffer must not be consumed")
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
        val src = readableBufferOf(patternBytes(1000)).asRawSource()
        val dst = BufferFactory.Default.allocate(1000)
        val moved = src.readInto(dst, 400L)
        assertEquals(400L, moved, "readInto honors byteCount")
        dst.resetForRead()
        assertEquals(400, dst.remaining(), "dst holds exactly 400 bytes")
    }

    @Test
    fun readInto_exactFit_sourceExhaustedAndDestinationFullSimultaneously_returnsFullAmount() {
        // Pins the boundary case where the last chunk both fills dst to capacity and
        // exhausts src at the same time: the loop must exit on the byteCount check
        // (dst-full) without issuing a further readAtMostTo probe that would observe EOF.
        val expected = patternBytes(256)
        val src = readableBufferOf(expected).asRawSource()
        val dst = BufferFactory.Default.allocate(256)
        val moved = src.readInto(dst, 256L)
        assertEquals(256L, moved, "readInto returns the full byteCount, not a truncated amount")
        dst.resetForRead()
        assertContentEquals(expected, ByteArray(256) { dst.readByte() })
    }

    @Test
    fun readInto_onAlreadyExhaustedSource_returnsZeroNotNegativeOne() {
        // RawSource.readAtMostTo() uses the -1 EOF convention, but readInto() folds that
        // into a plain 0 total — it never propagates -1 to its own caller.
        val src = readableBufferOf(ByteArray(0)).asRawSource()
        val dst = BufferFactory.Default.allocate(16)
        val moved = src.readInto(dst)
        assertEquals(0L, moved, "readInto returns 0, never -1, when the source starts at EOF")
    }

    @Test
    fun transferTo_movesEntireSource() {
        val expected = patternBytes(5000)
        val src = readableBufferOf(expected).asRawSource()
        val dst = BufferFactory.Default.allocate(5000)
        val moved = src.transferTo(dst)
        assertEquals(5000L, moved)
        dst.resetForRead()
        assertContentEquals(expected, ByteArray(5000) { dst.readByte() })
    }
}
