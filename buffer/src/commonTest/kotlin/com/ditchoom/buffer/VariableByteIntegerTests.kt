package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VariableByteIntegerTests {
    @Test
    fun encodedValueMustUseMinNumberOfBytes() {
        assertEquals(1, variableByteSize(0))
        assertEquals(1, variableByteSize(127))
        assertEquals(2, variableByteSize(128))
        assertEquals(2, variableByteSize(16_383))
        assertEquals(3, variableByteSize(16_384))
        assertEquals(3, variableByteSize(2_097_151))
        assertEquals(4, variableByteSize(2_097_152))
        assertEquals(4, variableByteSize(268_435_455))
    }

    @Test
    fun handles0() = roundTrip(0, 1)

    @Test
    fun handles1() = roundTrip(1, 1)

    @Test
    fun handles127() = roundTrip(127, 1)

    @Test
    fun handles128() = roundTrip(128, 2)

    @Test
    fun handles16383() = roundTrip(16383, 2)

    @Test
    fun handles16384() = roundTrip(16384, 3)

    @Test
    fun handles65535() = roundTrip(65535, 3)

    @Test
    fun handlesMaxMinus1() = roundTrip(VARIABLE_BYTE_INT_MAX - 1, 4)

    @Test
    fun handlesMax() = roundTrip(VARIABLE_BYTE_INT_MAX, 4)

    @Test
    fun handlesMaxPlus1() {
        val buffer = BufferFactory.Default.allocate(4)
        assertFailsWith<IllegalArgumentException> {
            buffer.writeVariableByteInteger(VARIABLE_BYTE_INT_MAX + 1)
        }
    }

    @Test
    fun negativeValueThrows() {
        val buffer = BufferFactory.Default.allocate(4)
        assertFailsWith<IllegalArgumentException> {
            buffer.writeVariableByteInteger(-1)
        }
    }

    @Test
    fun negativeSizeThrows() {
        assertFailsWith<IllegalArgumentException> {
            variableByteSize(-1)
        }
    }

    @Test
    fun malformedInputAllContinuationBytesThrows() {
        // 4 continuation bytes (all with high bit set) followed by no terminator
        val buffer = BufferFactory.Default.allocate(4)
        buffer.writeByte(0x80.toByte())
        buffer.writeByte(0x80.toByte())
        buffer.writeByte(0x80.toByte())
        buffer.writeByte(0x80.toByte())
        buffer.resetForRead()
        assertFailsWith<IllegalArgumentException> {
            buffer.readVariableByteInteger()
        }
    }

    @Test
    fun malformedInputTruncatedStreamThrows() {
        // A single continuation byte with no following data
        val buffer = BufferFactory.Default.allocate(1)
        buffer.writeByte(0x80.toByte()) // continuation bit set but no next byte
        buffer.resetForRead()
        assertFailsWith<IllegalArgumentException> {
            buffer.readVariableByteInteger()
        }
    }

    private fun roundTrip(
        value: Int,
        expectedSize: Int,
    ) {
        val buffer = BufferFactory.Default.allocate(expectedSize)
        buffer.writeVariableByteInteger(value)
        buffer.resetForRead()
        assertEquals(value, buffer.readVariableByteInteger(), "Failed round-trip for value $value")
    }
}
