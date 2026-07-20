package com.ditchoom.buffer.codec.test.protocols.boundary

import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.EncodeException
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.encodeToPlatformBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Regression coverage for a terminal `@LengthPrefixed val: @ProtocolMessage`
 * whose nested body has a BackPatch wireSize (it carries its own
 * `@LengthPrefixed String`). The parent's wireSize used to cast the nested
 * codec's result `as WireSize.Exact` (ClassCastException on every encode);
 * it must degrade to BackPatch, and encode must reserve-and-back-patch the
 * prefix slot.
 *
 * Also pins the prefix-range guard: an oversized body under a 1-byte prefix
 * must throw `EncodeException`, never silently mask-truncate the prefix
 * into corrupt framing.
 */
class LpNestedMessageBackPatchTest {
    @Test
    fun wireSizeDegradesToBackPatchNotThrow() {
        val msg = LpWrappedTail(kind = 1, body = WrappedLabel("hello"))
        assertEquals(WireSize.BackPatch, LpWrappedTailCodec.wireSize(msg, EncodeContext.Empty))
    }

    @Test
    fun roundTripsAcrossCapacityBoundary() {
        // The nested body's own string sweep walks both the inner string's
        // prefix and the outer body's back-patched prefix across the 64- and
        // 128-byte estimate boundaries.
        for (len in 0..140) {
            val msg = LpWrappedTail(kind = 7, body = WrappedLabel("x".repeat(len)))
            val buf = LpWrappedTailCodec.encodeToPlatformBuffer(msg)
            assertEquals(msg, LpWrappedTailCodec.decode(buf, DecodeContext.Empty), "label length $len")
        }
    }

    @Test
    fun oversizedBodyUnderBytePrefixThrowsEncodeException() {
        // body = 2-byte inner prefix + 300 chars = 302 bytes > 255.
        val msg = LpByteWrappedTail(body = WrappedLabel("x".repeat(300)))
        assertFailsWith<EncodeException> { LpByteWrappedTailCodec.encodeToPlatformBuffer(msg) }
    }

    @Test
    fun maxFittingBodyUnderBytePrefixRoundTrips() {
        // 253 chars + 2-byte inner prefix = 255 bytes — exactly the Byte max.
        val msg = LpByteWrappedTail(body = WrappedLabel("x".repeat(253)))
        val buf = LpByteWrappedTailCodec.encodeToPlatformBuffer(msg)
        assertEquals(msg, LpByteWrappedTailCodec.decode(buf, DecodeContext.Empty))
    }
}
