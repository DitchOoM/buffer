package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the buffer-to-buffer hex encode/decode primitives (encodeHexInto / decodeHexInto) on
 * [ReadBuffer]. Covers known vectors, round-trips, case, position semantics, error handling, and
 * transparency through pool wrappers.
 */
class HexCodecTest {
    private fun bytesBuffer(values: List<Int>): PlatformBuffer {
        val b = BufferFactory.Default.allocate(maxOf(values.size, 1))
        for (v in values) b.writeByte(v.toByte())
        b.resetForRead()
        return b
    }

    private fun textBuffer(text: String): PlatformBuffer {
        val b = BufferFactory.Default.allocate(maxOf(text.length, 1))
        b.writeString(text)
        b.resetForRead()
        return b
    }

    // region encode

    @Test
    fun encodesKnownVectorLowercase() {
        val src = bytesBuffer(listOf(0x00, 0x0F, 0xF0, 0xFF, 0x12, 0xAB))
        val dest = BufferFactory.Default.allocate(12)
        src.encodeHexInto(dest)
        dest.resetForRead()
        assertEquals("000ff0ff12ab", dest.readString(12))
    }

    @Test
    fun encodesUppercase() {
        val src = bytesBuffer(listOf(0xDE, 0xAD, 0xBE, 0xEF))
        val dest = BufferFactory.Default.allocate(8)
        src.encodeHexInto(dest, upperCase = true)
        dest.resetForRead()
        assertEquals("DEADBEEF", dest.readString(8))
    }

    @Test
    fun encodeEmptyWritesNothing() {
        val src = bytesBuffer(emptyList())
        val dest = BufferFactory.Default.allocate(4)
        src.encodeHexInto(dest)
        assertEquals(0, dest.position())
    }

    @Test
    fun absoluteEncodeDoesNotChangeSourcePosition() {
        val src = bytesBuffer(listOf(0x12, 0x34, 0x56))
        val dest = BufferFactory.Default.allocate(4)
        src.encodeHexInto(dest, offset = 1, length = 2)
        dest.resetForRead()
        assertEquals("3456", dest.readString(4))
        assertEquals(0, src.position())
    }

    @Test
    fun relativeEncodeAdvancesSourceToLimit() {
        val src = bytesBuffer(listOf(0x01, 0x02))
        val dest = BufferFactory.Default.allocate(4)
        src.encodeHexInto(dest)
        assertEquals(src.limit(), src.position())
        assertEquals(4, dest.position())
    }

    // endregion

    // region decode

    @Test
    fun decodesMixedCase() {
        val src = textBuffer("deADbeEF")
        val dest = BufferFactory.Default.allocate(4)
        src.decodeHexInto(dest)
        dest.resetForRead()
        assertEquals(0xDE, dest.readByte().toInt() and 0xFF)
        assertEquals(0xAD, dest.readByte().toInt() and 0xFF)
        assertEquals(0xBE, dest.readByte().toInt() and 0xFF)
        assertEquals(0xEF, dest.readByte().toInt() and 0xFF)
    }

    @Test
    fun oddLengthDecodeThrows() {
        val src = textBuffer("abc")
        val dest = BufferFactory.Default.allocate(2)
        assertFailsWith<IllegalArgumentException> { src.decodeHexInto(dest) }
    }

    @Test
    fun invalidCharDecodeThrows() {
        val src = textBuffer("12zz")
        val dest = BufferFactory.Default.allocate(2)
        assertFailsWith<IllegalArgumentException> { src.decodeHexInto(dest) }
    }

    // endregion

    // region round-trip

    @Test
    fun roundTripsArbitraryContent() {
        val original = textBuffer("The quick brown fox jumps over 13 lazy dogs.")
        val n = original.remaining()

        val hex = BufferFactory.Default.allocate(n * 2)
        original.encodeHexInto(hex)
        hex.resetForRead()

        val decoded = BufferFactory.Default.allocate(n)
        hex.decodeHexInto(decoded)
        decoded.resetForRead()
        original.resetForRead()

        assertTrue(original.contentEquals(decoded), "decode(encode(x)) must equal x")
    }

    @Test
    fun roundTripsAllByteValues() {
        // Exercises every nibble combination and the bulk/tail boundaries (256 bytes).
        val src = bytesBuffer((0..255).toList())
        val hex = BufferFactory.Default.allocate(512)
        src.encodeHexInto(hex)
        hex.resetForRead()

        val decoded = BufferFactory.Default.allocate(256)
        hex.decodeHexInto(decoded)
        decoded.resetForRead()
        src.resetForRead()

        assertTrue(src.contentEquals(decoded))
    }

    @Test
    fun roundTripsWithManagedDestination() {
        // On native, source is a NativeBuffer but the managed() dest is not NativeMemoryAccess, so this
        // exercises the native override's portable fallback branch (and the all-managed path elsewhere).
        val original = textBuffer("zero-copy hex, managed sink")
        val n = original.remaining()

        val hex = BufferFactory.managed().allocate(n * 2)
        original.encodeHexInto(hex)
        hex.resetForRead()

        val decoded = BufferFactory.managed().allocate(n)
        hex.decodeHexInto(decoded)
        decoded.resetForRead()
        original.resetForRead()

        assertTrue(original.contentEquals(decoded))
    }

    // endregion

    // region wrapper transparency

    @Test
    fun encodesThroughPooledBufferWrappers() {
        BufferPool().let { pool ->
            val src = pool.acquire(4)
            src.writeByte(0xAB.toByte())
            src.writeByte(0xCD.toByte())
            src.resetForRead()

            val dest = pool.acquire(4)
            src.encodeHexInto(dest)
            dest.resetForRead()
            assertEquals("abcd", dest.readString(4))

            pool.release(src)
            pool.release(dest)
            pool.clear()
        }
    }

    @Test
    fun decodesThroughPooledBufferWrappers() {
        BufferPool().let { pool ->
            val src = pool.acquire(4)
            src.writeString("00ff")
            src.resetForRead()

            val dest = pool.acquire(2)
            src.decodeHexInto(dest)
            dest.resetForRead()
            assertEquals(0x00, dest.readByte().toInt() and 0xFF)
            assertEquals(0xFF, dest.readByte().toInt() and 0xFF)

            pool.release(src)
            pool.release(dest)
            pool.clear()
        }
    }

    // endregion
}
