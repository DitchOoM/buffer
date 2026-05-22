package com.ditchoom.buffer.codec.test.protocols.asciistring

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.AsciiStringCodec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.WireSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AsciiStringCodecTest {
    @Test
    fun roundTripAsciiText() {
        val original = "STOMP"
        val buffer = BufferFactory.Default.allocate(64)
        AsciiStringCodec.encode(buffer, original, EncodeContext.Empty)
        buffer.resetForRead()
        assertEquals(original.length, buffer.remaining())
        val decoded = AsciiStringCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripEmptyString() {
        val buffer = BufferFactory.Default.allocate(8)
        AsciiStringCodec.encode(buffer, "", EncodeContext.Empty)
        buffer.resetForRead()
        assertEquals(0, buffer.remaining())
        // Bound the buffer to the (empty) body before decoding.
        buffer.setLimit(buffer.position())
        assertEquals("", AsciiStringCodec.decode(buffer, DecodeContext.Empty))
    }

    @Test
    fun roundTripFullAsciiRange() {
        val original = (0..0x7F).map { it.toChar() }.joinToString("")
        val buffer = BufferFactory.Default.allocate(256)
        AsciiStringCodec.encode(buffer, original, EncodeContext.Empty)
        buffer.resetForRead()
        assertEquals(0x80, buffer.remaining())
        val decoded = AsciiStringCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun wireSizeIsOneBytePerCharacter() {
        assertEquals(WireSize.Exact(0), AsciiStringCodec.wireSize("", EncodeContext.Empty))
        assertEquals(WireSize.Exact(5), AsciiStringCodec.wireSize("hello", EncodeContext.Empty))
        assertEquals(WireSize.Exact(13), AsciiStringCodec.wireSize("hello, world!", EncodeContext.Empty))
    }

    @Test
    fun encodeRejectsCharactersAbove0x7F() {
        val buffer = BufferFactory.Default.allocate(32)
        val ex =
            assertFailsWith<EncodeException> {
                AsciiStringCodec.encode(buffer, "café", EncodeContext.Empty)
            }
        assertEquals("AsciiStringCodec", ex.fieldPath)
        assertTrue("index 3" in ex.reason, "diagnostic should name the offending index: ${ex.reason}")
        assertTrue("U+00E9" in ex.reason, "diagnostic should name the offending codepoint: ${ex.reason}")
    }

    @Test
    fun encodeRejectsHighCodepointAtFirstCharacter() {
        val buffer = BufferFactory.Default.allocate(32)
        val ex =
            assertFailsWith<EncodeException> {
                AsciiStringCodec.encode(buffer, "€USD", EncodeContext.Empty)
            }
        assertTrue("index 0" in ex.reason, "diagnostic should name the offending index: ${ex.reason}")
        assertTrue("U+20AC" in ex.reason, "diagnostic should name the euro-sign codepoint: ${ex.reason}")
    }

    @Test
    fun decodeReadsRemainingBytes() {
        // Simulate an outer-bounded slice: caller has narrowed the limit to body length.
        val buffer = BufferFactory.Default.allocate(32)
        buffer.writeByte('H'.code.toByte())
        buffer.writeByte('i'.code.toByte())
        buffer.writeByte(0x42)
        buffer.resetForRead()
        buffer.setLimit(2)
        val decoded = AsciiStringCodec.decode(buffer, DecodeContext.Empty)
        assertEquals("Hi", decoded)
    }

    @Test
    fun lengthPrefixedFixtureRoundTrips() {
        val original = AsciiGreeting(command = "CONNECT")
        val buffer = BufferFactory.Default.allocate(32)
        AsciiGreetingCodec.encode(buffer, original, EncodeContext.Empty)
        buffer.resetForRead()
        // 2-byte UShort BE prefix + 7 ASCII bytes = 9.
        assertEquals(9, buffer.remaining())
        val decoded = AsciiGreetingCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun lengthPrefixedFixtureRoundTripsEmpty() {
        val original = AsciiGreeting(command = "")
        val buffer = BufferFactory.Default.allocate(8)
        AsciiGreetingCodec.encode(buffer, original, EncodeContext.Empty)
        buffer.resetForRead()
        // 2-byte UShort BE prefix carrying 0; no body.
        assertEquals(2, buffer.remaining())
        assertEquals(original, AsciiGreetingCodec.decode(buffer, DecodeContext.Empty))
    }

    @Test
    fun lengthPrefixedFixtureWireBytesMatchManualLayout() {
        val original = AsciiGreeting(command = "SEND")
        val actual = BufferFactory.Default.allocate(16)
        AsciiGreetingCodec.encode(actual, original, EncodeContext.Empty)
        actual.resetForRead()

        val expected = BufferFactory.Default.allocate(16)
        expected.writeUShort(4u)
        expected.writeByte('S'.code.toByte())
        expected.writeByte('E'.code.toByte())
        expected.writeByte('N'.code.toByte())
        expected.writeByte('D'.code.toByte())
        expected.resetForRead()

        assertEquals(expected.remaining(), actual.remaining())
        while (expected.remaining() > 0) {
            assertEquals(expected.readByte(), actual.readByte())
        }
    }
}
