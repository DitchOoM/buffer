package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    @Test
    fun lengthPrefixedFastPathEmptyBody() {
        // Buffer has plenty of room — fast path should reserve maxBytes, encode 0 bytes,
        // shift the placeholder back, and write a single 0x00 VBI.
        val buffer = BufferFactory.Default.allocate(8)
        buffer.writeVariableByteIntegerLengthPrefixed(maxBytes = 4) { /* empty body */ }
        assertEquals(1, buffer.position(), "should write only 1 byte")
        buffer.resetForRead()
        assertEquals(0x00.toByte(), buffer.readByte())
    }

    @Test
    fun lengthPrefixedSlowPathTightBufferEmptyBody() {
        // Buffer sized exactly for the wire (1 byte for VBI=0). Fast path can't reserve
        // 4 bytes, so the slow path encodes to scratch (0 bytes) and writes VBI=0 directly.
        val buffer = BufferFactory.Default.allocate(1)
        buffer.writeVariableByteIntegerLengthPrefixed(maxBytes = 4) { /* empty body */ }
        assertEquals(1, buffer.position())
        buffer.resetForRead()
        assertEquals(0x00.toByte(), buffer.readByte())
    }

    @Test
    fun lengthPrefixedSlowPathTightBufferNonEmptyBody() {
        // Buffer sized exactly for VBI(1)=0x05 + 5 body bytes. Fast path can't reserve
        // cap=4, so slow path: encode 5 bytes to scratch, write VBI(5), copy scratch.
        val buffer = BufferFactory.Default.allocate(6)
        buffer.writeVariableByteIntegerLengthPrefixed(maxBytes = 4) { inner ->
            inner.writeByte(0x10.toByte())
            inner.writeByte(0x20.toByte())
            inner.writeByte(0x30.toByte())
            inner.writeByte(0x40.toByte())
            inner.writeByte(0x50.toByte())
        }
        assertEquals(6, buffer.position())
        buffer.resetForRead()
        assertEquals(0x05.toByte(), buffer.readByte(), "VBI prefix = 5")
        assertEquals(0x10.toByte(), buffer.readByte())
        assertEquals(0x20.toByte(), buffer.readByte())
        assertEquals(0x30.toByte(), buffer.readByte())
        assertEquals(0x40.toByte(), buffer.readByte())
        assertEquals(0x50.toByte(), buffer.readByte())
    }

    @Test
    fun lengthPrefixedFastAndSlowPathMatch() {
        // Same body bytes via both code paths must produce identical wire output.
        val body = ByteArray(50) { (it * 3).toByte() }

        val fast = BufferFactory.Default.allocate(64)
        fast.writeVariableByteIntegerLengthPrefixed(maxBytes = 4) { it.write(body) }
        val fastPos = fast.position()
        fast.resetForRead()
        val fastBytes = ByteArray(fastPos) { fast.readByte() }

        // Slow path: buffer sized exactly so reservation can't fit (1-byte VBI + 50 = 51).
        val slow = BufferFactory.Default.allocate(51)
        slow.writeVariableByteIntegerLengthPrefixed(maxBytes = 4) { it.write(body) }
        val slowPos = slow.position()
        slow.resetForRead()
        val slowBytes = ByteArray(slowPos) { slow.readByte() }

        assertContentEquals(fastBytes, slowBytes, "fast and slow path must produce identical wire bytes")
    }
}
