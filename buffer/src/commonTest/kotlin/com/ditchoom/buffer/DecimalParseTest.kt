package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for the textual decimal/integer parsers added to [ReadBuffer]
 * (readFixedDecimalTenths / readSignedInt / readDecimalAsLong).
 *
 * Inputs are written as UTF-8 strings to exercise the real decode path; digits, '-' and '.'
 * are byte-identical in ASCII and UTF-8.
 */
class DecimalParseTest {
    private fun bufferOf(text: String): PlatformBuffer {
        val buffer = BufferFactory.Default.allocate(text.length)
        buffer.writeString(text)
        buffer.resetForRead()
        return buffer
    }

    // region readFixedDecimalTenths (absolute)

    @Test
    fun fixedDecimalNegative() {
        val b = bufferOf("-12.3")
        assertEquals(-123, b.readFixedDecimalTenths(0, 5))
    }

    @Test
    fun fixedDecimalSingleIntegerDigit() {
        val b = bufferOf("5.0")
        assertEquals(50, b.readFixedDecimalTenths(0, 3))
    }

    @Test
    fun fixedDecimalZero() {
        val b = bufferOf("0.0")
        assertEquals(0, b.readFixedDecimalTenths(0, 3))
    }

    @Test
    fun fixedDecimalMaxMagnitude() {
        val b = bufferOf("99.9")
        assertEquals(999, b.readFixedDecimalTenths(0, 4))
    }

    @Test
    fun fixedDecimalNegativeFraction() {
        val b = bufferOf("-0.5")
        assertEquals(-5, b.readFixedDecimalTenths(0, 4))
    }

    @Test
    fun fixedDecimalAtOffsetWithinLine() {
        // Station;Temp — parse only the temperature field by its boundaries.
        val line = "Hamburg;-12.3"
        val b = bufferOf(line)
        val semi = line.indexOf(';')
        val tempOffset = semi + 1
        val tempLength = line.length - tempOffset
        assertEquals(-123, b.readFixedDecimalTenths(tempOffset, tempLength))
    }

    @Test
    fun fixedDecimalDoesNotChangePositionForAbsolute() {
        val b = bufferOf("7.5")
        val startPos = b.position()
        b.readFixedDecimalTenths(0, 3)
        assertEquals(startPos, b.position())
    }

    // region readFixedDecimalTenths (relative)

    @Test
    fun fixedDecimalRelativeAdvancesPosition() {
        val b = bufferOf("8.1")
        assertEquals(81, b.readFixedDecimalTenths(3))
        assertEquals(3, b.position())
        assertEquals(0, b.remaining())
    }

    // region readSignedInt

    @Test
    fun signedIntPositive() {
        val b = bufferOf("123")
        assertEquals(123, b.readSignedInt(0, 3))
    }

    @Test
    fun signedIntNegative() {
        val b = bufferOf("-7")
        assertEquals(-7, b.readSignedInt(0, 2))
    }

    @Test
    fun signedIntRelativeAdvancesPosition() {
        val b = bufferOf("42")
        assertEquals(42, b.readSignedInt(2))
        assertEquals(2, b.position())
    }

    // region readDecimalAsLong

    @Test
    fun decimalAsLongLargeValue() {
        val b = bufferOf("1234567890")
        assertEquals(1234567890L, b.readDecimalAsLong(0, 10))
    }

    @Test
    fun decimalAsLongNegative() {
        val b = bufferOf("-9000000000")
        assertEquals(-9000000000L, b.readDecimalAsLong(0, 11))
    }

    // region backend parity

    @Test
    fun parsesIdenticallyOnManagedBackend() {
        val text = "-42.7"
        val managed = BufferFactory.managed().allocate(text.length)
        managed.writeString(text)
        managed.resetForRead()
        assertEquals(-427, managed.readFixedDecimalTenths(0, text.length))
    }
}
