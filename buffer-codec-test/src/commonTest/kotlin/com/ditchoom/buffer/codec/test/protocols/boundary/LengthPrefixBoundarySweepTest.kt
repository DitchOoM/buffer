package com.ditchoom.buffer.codec.test.protocols.boundary

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.encodeToPlatformBuffer
import com.ditchoom.buffer.codec.test.protocols.count.CountNamed
import com.ditchoom.buffer.codec.test.protocols.count.CountVariableList
import com.ditchoom.buffer.codec.test.protocols.count.CountVariableListCodec
import com.ditchoom.buffer.codec.test.protocols.simple.TwoStrings
import com.ditchoom.buffer.codec.test.protocols.simple.TwoStringsCodec
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression sweeps for the length-prefix reservation at a capacity
 * boundary. `encodeToPlatformBuffer` starts BackPatch shapes at a 64-byte
 * estimate and doubles on `BufferOverflowException`; a prefix reservation
 * that forward-seeks (`buffer.position(pos + width)`) past the current
 * limit instead threw `IllegalArgumentException`, which escaped the
 * grow-and-retry loop. Sweeping the leading field's length walks the next
 * prefix across both sides of the 64- and 128-byte boundaries — every
 * point must round-trip.
 */
class LengthPrefixBoundarySweepTest {
    private fun <T> assertRoundTrips(
        codec: Codec<T>,
        value: T,
    ) {
        val buf = codec.encodeToPlatformBuffer(value)
        assertEquals(value, codec.decode(buf, DecodeContext.Empty), "round-trip for $value")
    }

    @Test
    fun twoStringsSecondPrefixAcross64And128() {
        // 2 (prefix a) + padLen (body a) puts b's 2-byte prefix at padLen+2:
        // padLen 55..75 walks it across 64, 119..135 across 128.
        for (padLen in 55..75) assertRoundTrips(TwoStringsCodec, TwoStrings("x".repeat(padLen), "y"))
        for (padLen in 119..135) assertRoundTrips(TwoStringsCodec, TwoStrings("x".repeat(padLen), "y"))
    }

    @Test
    fun fourBytePrefixAcross64And128() {
        for (padLen in 55..75) assertRoundTrips(BigTailCodec, BigTail("x".repeat(padLen), "y"))
        for (padLen in 119..135) assertRoundTrips(BigTailCodec, BigTail("x".repeat(padLen), "y"))
    }

    @Test
    fun valueClassPrefixAcross64And128() {
        for (padLen in 55..75) assertRoundTrips(WithVidCodec, WithVid("x".repeat(padLen), BoundaryVid("id")))
        for (padLen in 119..135) assertRoundTrips(WithVidCodec, WithVid("x".repeat(padLen), BoundaryVid("id")))
    }

    @Test
    fun nestedSealedPrefixAcross64And128() {
        for (len in 40..140) {
            assertRoundTrips(BoundaryHostCodec, BoundaryHost("h".repeat(len), BoundaryDisp.Named("x")))
        }
        // Singleton variant: only the host string and the discriminator byte.
        for (len in 55..70) {
            assertRoundTrips(BoundaryHostCodec, BoundaryHost("h".repeat(len), BoundaryDisp.Inherits))
        }
    }

    @Test
    fun countListElementPrefixCrossesBoundary() {
        // Composes both bugs: a @Count list of BackPatch elements whose
        // second/third element prefix crosses the 64-byte boundary.
        assertRoundTrips(CountVariableListCodec, CountVariableList(List(3) { CountNamed("z".repeat(30)) }))
        // Sweep the element size so the crossing lands at many offsets.
        for (elemLen in 18..34) {
            assertRoundTrips(CountVariableListCodec, CountVariableList(List(3) { CountNamed("z".repeat(elemLen)) }))
        }
    }
}
