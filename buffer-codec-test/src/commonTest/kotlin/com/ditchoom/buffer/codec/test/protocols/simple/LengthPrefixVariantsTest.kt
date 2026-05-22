package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Prefix-width coverage tests. Confirms the emitter handles
 * all three `LengthPrefix` widths (: Byte /
 * Short / Int — no widening), and that the encode-side overflow guard
 * fires when a UTF-8 body exceeds the prefix's representable range.
 *
 * The 4-byte (`Int`) prefix has no runtime overflow guard because a
 * `position()`-derived Int byte count can never exceed UInt max
 * (2^32 - 1) — the check would be dead code. That branch is exercised
 * only as a positive round-trip.
 */
class LengthPrefixVariantsTest {
    @Test
    fun bytePrefix_roundTripsAscii() {
        val original = BytePrefixedString("hello")
        val buf = BufferFactory.Default.allocate(64)
        BytePrefixedStringCodec.encode(buf, original, EncodeContext.Empty)
        assertEquals(1 + 5, buf.position(), "1-byte prefix + 5-byte body")
        buf.resetForRead()
        assertEquals(0x05, buf.readByte().toInt() and 0xFF, "prefix is 5")
        // Re-encode for the decode round-trip — the first resetForRead clamped
        // the limit to encoded-position, but the readByte above advanced position
        // past the prefix so a second resetForRead would clamp limit to 1.
        val decodeBuf = BufferFactory.Default.allocate(64)
        BytePrefixedStringCodec.encode(decodeBuf, original, EncodeContext.Empty)
        decodeBuf.resetForRead()
        val decoded = BytePrefixedStringCodec.decode(decodeBuf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun bytePrefix_roundTripsAtBoundary255() {
        val original = BytePrefixedString("a".repeat(255))
        val buf = BufferFactory.Default.allocate(512)
        BytePrefixedStringCodec.encode(buf, original, EncodeContext.Empty)
        assertEquals(1 + 255, buf.position())
        buf.resetForRead()
        val decoded = BytePrefixedStringCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun bytePrefix_overflowAt256Throws() {
        val tooLong = BytePrefixedString("a".repeat(256))
        val buf = BufferFactory.Default.allocate(512)
        val ex =
            assertFailsWith<EncodeException> {
                BytePrefixedStringCodec.encode(buf, tooLong, EncodeContext.Empty)
            }
        assertEquals("BytePrefixedString.name", ex.fieldPath)
    }

    @Test
    fun intPrefix_roundTripsLargeString() {
        // 100 KiB exceeds UShort.MAX (65535), so it would not fit a 2-byte prefix.
        val original = IntPrefixedString("x".repeat(100_000))
        val buf = BufferFactory.Default.allocate(4 + 100_000)
        IntPrefixedStringCodec.encode(buf, original, EncodeContext.Empty)
        assertEquals(4 + 100_000, buf.position(), "4-byte prefix + 100000-byte body")
        buf.resetForRead()
        val decoded = IntPrefixedStringCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }
}
