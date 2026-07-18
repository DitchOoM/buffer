package com.ditchoom.buffer.codec.test.protocols.valueclassstring

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip and wire-golden coverage for `@JvmInline value
 * class`-over-`String` fields under each String framing, plus the
 * `@When`-nullable combination. The central guarantee is byte-identity
 * with a plain `String` field under the same framing — the value class
 * only wraps/unwraps at the decode/encode boundary and changes no wire
 * bytes.
 *
 * All fixture text is ASCII, so a string's UTF-8 byte length equals its
 * `.length`.
 */
class ValueClassStringIdsCodecTest {
    // ---- @LengthPrefixed round-trip (default 2-byte prefix) -------------

    @Test
    fun lengthPrefixedValueClassRoundTrips() {
        val original = LpValueClassId(UserId("hello"))
        val buf = encode { LpValueClassIdCodec.encode(it, original, EncodeContext.Empty) }
        // 2-byte prefix + 5 body bytes.
        assertEquals(7, buf.position())
        buf.resetForRead()
        assertEquals(original, LpValueClassIdCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun lengthPrefixedValueClassRoundTripsEmpty() {
        val original = LpValueClassId(UserId(""))
        val buf = encode { LpValueClassIdCodec.encode(it, original, EncodeContext.Empty) }
        assertEquals(2, buf.position())
        buf.resetForRead()
        assertEquals(original, LpValueClassIdCodec.decode(buf, DecodeContext.Empty))
    }

    // ---- byte-identity with a plain @LengthPrefixed String --------------

    @Test
    fun lengthPrefixedValueClassIsByteIdenticalToPlainString() {
        val text = "session-42"
        val fromValueClass =
            encode { LpValueClassIdCodec.encode(it, LpValueClassId(UserId(text)), EncodeContext.Empty) }
                .also { it.resetForRead() }
        val fromPlainString =
            encode { LpPlainStringIdCodec.encode(it, LpPlainStringId(text), EncodeContext.Empty) }
                .also { it.resetForRead() }
        assertTrue(
            fromValueClass.contentEquals(fromPlainString),
            "value-class-over-String field must be byte-identical to a plain @LengthPrefixed String",
        )
        // Explicit golden: 2-byte BE prefix (0x000A = 10) + "session-42".
        val expected =
            encode {
                it.writeShort(text.length.toShort())
                it.writeString(text, Charset.UTF8)
            }.also { it.resetForRead() }
        assertTrue(fromValueClass.contentEquals(expected), "golden bytes mismatch")
    }

    @Test
    fun lengthPrefixedValueClassWireSizeIsBackPatch() {
        assertEquals(
            WireSize.BackPatch,
            LpValueClassIdCodec.wireSize(LpValueClassId(UserId("x")), EncodeContext.Empty),
        )
    }

    // ---- @LengthPrefixed, explicit prefix widths ------------------------

    @Test
    fun bytePrefixValueClassRoundTrips() {
        val original = LpByteValueClassId(UserId("abc"))
        val buf = encode { LpByteValueClassIdCodec.encode(it, original, EncodeContext.Empty) }
        assertEquals(1 + 3, buf.position())
        buf.resetForRead()
        assertEquals(0x03.toByte(), buf.readByte(), "1-byte length prefix")
        buf.position(0) // rewind without re-flipping the limit
        assertEquals(original, LpByteValueClassIdCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun intPrefixValueClassRoundTrips() {
        val original = LpIntValueClassId(TraceId("deadbeef"))
        val buf = encode { LpIntValueClassIdCodec.encode(it, original, EncodeContext.Empty) }
        assertEquals(4 + 8, buf.position())
        buf.resetForRead()
        assertEquals(8, buf.readInt(), "4-byte length prefix")
        buf.position(0) // rewind without re-flipping the limit
        assertEquals(original, LpIntValueClassIdCodec.decode(buf, DecodeContext.Empty))
    }

    // ---- @When (nullable) + @LengthPrefixed value class -----------------

    @Test
    fun optionalValueClassPresentRoundTrips() {
        val original = OptionalValueClassId(hasId = true, id = UserId("u1"))
        val buf = encode { OptionalValueClassIdCodec.encode(it, original, EncodeContext.Empty) }
        // presence flag (1) + prefix (2) + body (2).
        assertEquals(1 + 2 + 2, buf.position())
        buf.resetForRead()
        assertEquals(original, OptionalValueClassIdCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun optionalValueClassAbsentRoundTrips() {
        val original = OptionalValueClassId(hasId = false, id = null)
        val buf = encode { OptionalValueClassIdCodec.encode(it, original, EncodeContext.Empty) }
        assertEquals(1, buf.position(), "only the false presence flag")
        buf.resetForRead()
        assertEquals(0x00.toByte(), buf.readByte())
        buf.position(0)
        assertEquals(original, OptionalValueClassIdCodec.decode(buf, DecodeContext.Empty))
    }

    // ---- @LengthFrom value class over String ----------------------------

    @Test
    fun lengthFromValueClassRoundTrips() {
        val text = "abcd"
        val original =
            LengthFromValueClassId(
                len = text.length.toUByte(),
                flags = 0x7F,
                payload = UserId(text),
            )
        val buf = encode { LengthFromValueClassIdCodec.encode(it, original, EncodeContext.Empty) }
        // len (1) + flags (1) + body (4). No inline prefix on the body.
        assertEquals(1 + 1 + 4, buf.position())
        buf.resetForRead()
        assertEquals(original, LengthFromValueClassIdCodec.decode(buf, DecodeContext.Empty))
    }

    // ---- @RemainingBytes value class over String ------------------------

    @Test
    fun remainingBytesValueClassRoundTrips() {
        val text = "trailing-id"
        val original = RemainingValueClassId(kind = 0x05, id = SessionId(text))
        val buf = encode { RemainingValueClassIdCodec.encode(it, original, EncodeContext.Empty) }
        // kind (1) + remaining UTF-8 bytes.
        assertEquals(1 + text.length, buf.position())
        buf.resetForRead()
        assertEquals(original, RemainingValueClassIdCodec.decode(buf, DecodeContext.Empty))
    }

    @Test
    fun remainingBytesValueClassEmptyRoundTrips() {
        val original = RemainingValueClassId(kind = 0x01, id = SessionId(""))
        val buf = encode { RemainingValueClassIdCodec.encode(it, original, EncodeContext.Empty) }
        assertEquals(1, buf.position())
        buf.resetForRead()
        assertEquals(original, RemainingValueClassIdCodec.decode(buf, DecodeContext.Empty))
    }

    // ---- helpers --------------------------------------------------------

    private fun encode(block: (PlatformBuffer) -> Unit) = BufferFactory.Default.allocate(256).also(block)
}
