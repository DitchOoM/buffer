package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.test.functions.PropertyBagCodec
import com.ditchoom.buffer.codec.test.protocols.ContextAwareRgbCodec
import com.ditchoom.buffer.codec.test.protocols.Rgb
import com.ditchoom.buffer.codec.test.protocols.RgbCodec
import com.ditchoom.buffer.codec.test.protocols.RgbOffsetKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guard 1 — payload-only round-trip assertions for every hand-written `Codec<T>` in this module.
 *
 * Each test allocates `wireSize` bytes, calls `encode`, asserts that `position` equals
 * `wireSize` (encode wrote exactly the byte count it claimed), then `decode`s and asserts
 * `remaining == 0` (decode consumed exactly the payload). Together these catch
 * re-introduction of self-bounding behaviour inside any `Codec<T>` body.
 *
 * Most generated codecs are exercised via `testRoundTrip(...)` elsewhere; that helper
 * already enforces both Guard 1 invariants. This file covers the hand-written codecs.
 */
class Guard1RoundTripTest {
    private fun <T> guard1(
        codec: Codec<T>,
        value: T,
        encodeContext: EncodeContext = EncodeContext.Empty,
        decodeContext: DecodeContext = DecodeContext.Empty,
    ): T {
        val payloadSize = codec.wireSize(value, encodeContext)
        val buffer = BufferFactory.Default.allocate(payloadSize, ByteOrder.BIG_ENDIAN)
        codec.encode(buffer, value, encodeContext)
        assertEquals(
            payloadSize,
            buffer.position(),
            "wireSize must equal encoded byte count for ${codec::class.simpleName}",
        )
        buffer.resetForRead()
        val decoded = codec.decode(buffer, decodeContext)
        assertEquals(
            0,
            buffer.remaining(),
            "decode must consume exactly the payload bytes for ${codec::class.simpleName}",
        )
        return decoded
    }

    @Test
    fun rgbCodecGuard1() {
        val original = Rgb(0xAAu, 0xBBu, 0xCCu)
        assertEquals(original, guard1(RgbCodec, original))
    }

    @Test
    fun rgbCodecGuard1AllZero() {
        val original = Rgb(0u, 0u, 0u)
        assertEquals(original, guard1(RgbCodec, original))
    }

    @Test
    fun rgbCodecGuard1AllMax() {
        val original = Rgb(UByte.MAX_VALUE, UByte.MAX_VALUE, UByte.MAX_VALUE)
        assertEquals(original, guard1(RgbCodec, original))
    }

    @Test
    fun contextAwareRgbCodecGuard1WithOffset() {
        val original = Rgb(100u, 50u, 25u)
        val ctx = EncodeContext.Empty.with(RgbOffsetKey, 5)
        val decodeCtx = DecodeContext.Empty.with(RgbOffsetKey, 5)
        // The codec subtracts on encode and adds on decode → round-trip is identity.
        assertEquals(original, guard1(ContextAwareRgbCodec, original, ctx, decodeCtx))
    }

    @Test
    fun contextAwareRgbCodecGuard1NoOffset() {
        val original = Rgb(0xFFu, 0x80u, 0x01u)
        assertEquals(original, guard1(ContextAwareRgbCodec, original))
    }

    @Test
    fun propertyBagCodecGuard1Empty() {
        val original: Map<Int, Int> = emptyMap()
        assertEquals(original, guard1(PropertyBagCodec, original))
    }

    @Test
    fun propertyBagCodecGuard1SingleEntry() {
        val original = mapOf(5 to 100)
        assertEquals(original, guard1(PropertyBagCodec, original))
    }

    @Test
    fun propertyBagCodecGuard1MultipleEntries() {
        val original = mapOf(1 to 42, 2 to 7, 3 to 99)
        assertEquals(original, guard1(PropertyBagCodec, original))
    }

    @Test
    fun propertyBagCodecGuard1MultiByteVbiValue() {
        // Value 128 forces a 2-byte VBI on the value field.
        val original = mapOf(1 to 128, 2 to 16384)
        assertEquals(original, guard1(PropertyBagCodec, original))
    }
}
