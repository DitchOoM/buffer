package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.ColorBlock
import com.ditchoom.buffer.codec.test.protocols.ColorBlockCodec
import com.ditchoom.buffer.codec.test.protocols.ColoredPoint
import com.ditchoom.buffer.codec.test.protocols.ColoredPointCodec
import com.ditchoom.buffer.codec.test.protocols.PrefixedColor
import com.ditchoom.buffer.codec.test.protocols.PrefixedColorCodec
import com.ditchoom.buffer.codec.test.protocols.Rgb
import com.ditchoom.buffer.codec.test.protocols.TrailingColor
import com.ditchoom.buffer.codec.test.protocols.TrailingColorCodec
import com.ditchoom.buffer.codec.testRoundTrip
import kotlin.test.Test
import kotlin.test.assertEquals

class UseCodecRoundTripTest {
    @Test
    fun useCodecDirectRoundTrip() {
        val original = ColoredPoint(x = 10, y = 20, color = Rgb(255u, 128u, 0u))
        val decoded = ColoredPointCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun useCodecDirectExactBytes() {
        val original = ColoredPoint(x = 0, y = 0, color = Rgb(0xAAu, 0xBBu, 0xCCu))
        val decoded =
            ColoredPointCodec.testRoundTrip(
                original,
                expectedBytes =
                    byteArrayOf(
                        0,
                        0,
                        0,
                        0, // x
                        0,
                        0,
                        0,
                        0, // y
                        0xAA.toByte(),
                        0xBB.toByte(),
                        0xCC.toByte(), // RGB
                    ),
            )
        assertEquals(original, decoded)
    }

    @Test
    fun useCodecWithLengthFromRoundTrip() {
        val original = ColorBlock(colorBytes = 3u, color = Rgb(10u, 20u, 30u), label = 42u)
        val decoded = ColorBlockCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun useCodecWithLengthPrefixedRoundTrip() {
        val original = PrefixedColor(id = 1u, color = Rgb(100u, 200u, 50u))
        val decoded = PrefixedColorCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun useCodecWithRemainingBytesRoundTrip() {
        val original = TrailingColor(id = 7u, color = Rgb(0u, 0u, 0u))
        val decoded = TrailingColorCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun useCodecPreservesFieldOrder() {
        val original = ColorBlock(colorBytes = 3u, color = Rgb(1u, 2u, 3u), label = 99u)
        val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        ColorBlockCodec.encode(buffer, original)
        buffer.resetForRead()
        // colorBytes (2 bytes) + rgb (3 bytes) + label (1 byte)
        assertEquals(3.toShort(), buffer.readShort()) // colorBytes = 3
        assertEquals(1.toByte(), buffer.readByte()) // r
        assertEquals(2.toByte(), buffer.readByte()) // g
        assertEquals(3.toByte(), buffer.readByte()) // b
        assertEquals(99.toByte(), buffer.readByte()) // label
    }
}
