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
    fun encodesIntoLittleEndianDestination() {
        // Hex output is an ASCII byte sequence, so it must be identical regardless of the destination's
        // byte order (default buffers are big-endian; this pins the little-endian case too).
        val src = bytesBuffer(listOf(0xDE, 0xAD, 0xBE, 0xEF))
        val dest = BufferFactory.Default.allocate(8, ByteOrder.LITTLE_ENDIAN)
        src.encodeHexInto(dest)
        dest.resetForRead()
        assertEquals("deadbeef", dest.readString(8))
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

    // region String conversions

    @Test
    fun toHexStringMatchesTheBufferToBufferEncoder() {
        val bytes = listOf(0x00, 0x0F, 0xF0, 0xFF, 0x12, 0xAB)
        val viaBuffer = BufferFactory.Default.allocate(bytes.size * 2)
        bytesBuffer(bytes).encodeHexInto(viaBuffer)
        viaBuffer.resetForRead()

        assertEquals(
            viaBuffer.readString(bytes.size * 2),
            bytesBuffer(bytes).toHexString(),
            "the String form must agree with the buffer-to-buffer primitive",
        )
    }

    @Test
    fun toHexStringEncodesKnownVectorInBothCases() {
        val src = bytesBuffer(listOf(0x00, 0x0F, 0xF0, 0xFF, 0x12, 0xAB))
        assertEquals("000ff0ff12ab", src.toHexString())
        assertEquals("000FF0FF12AB", src.toHexString(upperCase = true))
    }

    /**
     * A value conversion must not consume its receiver — unlike the relative `encodeHexInto`, which
     * deliberately advances. Logging a buffer and then reading it is the whole point.
     */
    @Test
    fun toHexStringDoesNotAdvancePosition() {
        val src = bytesBuffer(listOf(0x01, 0x02, 0x03))
        val before = src.position()
        assertEquals("010203", src.toHexString())
        assertEquals(before, src.position(), "toHexString must not consume the buffer")
        assertEquals(0x01, src.readByte().toInt() and 0xFF, "the buffer is still fully readable")
    }

    @Test
    fun toHexStringHonoursAnAbsoluteRange() {
        val src = bytesBuffer(listOf(0xDE, 0xAD, 0xBE, 0xEF))
        assertEquals("adbe", src.toHexString(offset = 1, length = 2))
    }

    @Test
    fun toHexStringOfEmptyIsEmpty() {
        assertEquals("", bytesBuffer(emptyList()).also { it.setLimit(0) }.toHexString())
    }

    @Test
    fun toHexStringRejectsAnOutOfRangeWindow() {
        val src = bytesBuffer(listOf(0x01, 0x02))
        assertFailsWith<BufferUnderflowException> { src.toHexString(offset = 1, length = 4) }
    }

    /**
     * `toHexString` builds the `String` directly and stages nothing, so a caller's allocator is never
     * touched. Guards the shape: reintroducing an intermediate buffer would either leak it (the
     * allocator never sees it back) or make this count non-zero.
     */
    @Test
    fun toHexStringAllocatesNoBuffer() {
        val counting = BufferFactory.Default.counting()
        val src = counting.allocate(2)
        src.writeByte(0x01)
        src.writeByte(0x02)
        src.resetForRead()
        val before = counting.allocationCount
        assertEquals("0102", src.toHexString())
        assertEquals(before, counting.allocationCount, "toHexString must not allocate a buffer")
    }

    /**
     * The factory is the receiver, and the sole allocation is the buffer handed back — so a pooled or
     * deterministic allocator owns the result, and there is no staged intermediate to leak.
     */
    @Test
    fun fromHexStringAllocatesOnlyTheResultThroughItsReceiver() {
        val counting = BufferFactory.Default.counting()
        val decoded = counting.fromHexString("0102")
        assertEquals(1, counting.allocationCount, "only the returned buffer may be allocated")
        assertEquals("0102", decoded.toHexString())
    }

    @Test
    fun fromHexStringRoundTripsThroughToHexString() {
        val hex = "000ff0ff12ab"
        val decoded = BufferFactory.Default.fromHexString(hex)
        assertEquals(hex.length / 2, decoded.remaining())
        assertEquals(hex, decoded.toHexString())
    }

    @Test
    fun fromHexStringAcceptsUpperCase() {
        assertEquals("deadbeef", BufferFactory.Default.fromHexString("DEADBEEF").toHexString())
    }

    @Test
    fun fromHexStringOfEmptyIsAnEmptyBuffer() {
        assertEquals(0, BufferFactory.Default.fromHexString("").remaining())
    }

    @Test
    fun fromHexStringHonoursTheRequestedByteOrder() {
        val le = BufferFactory.Default.fromHexString("0102", ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x0201.toShort(), le.readShort())
    }

    @Test
    fun fromHexStringRejectsOddLength() {
        assertFailsWith<IllegalArgumentException> { BufferFactory.Default.fromHexString("abc") }
    }

    @Test
    fun fromHexStringRejectsNonHexCharacter() {
        assertFailsWith<IllegalArgumentException> { BufferFactory.Default.fromHexString("zz") }
    }

    /**
     * Non-ASCII must be rejected as a non-hex character, not narrowed to a byte first: U+0161 truncates
     * to `'a'` and would otherwise decode as the nibble 10.
     */
    @Test
    fun fromHexStringRejectsNonAsciiThatNarrowsOntoAHexDigit() {
        assertFailsWith<IllegalArgumentException> { BufferFactory.Default.fromHexString("š0") }
    }

    // endregion
}
