package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.FlagByte
import com.ditchoom.buffer.codec.test.protocols.RangeFrame
import com.ditchoom.buffer.codec.test.protocols.RangeFrameCodec
import com.ditchoom.buffer.codec.testRoundTrip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Walks every wire byte in the synthetic 0x30..0x3F range through encode → decode and verifies:
 *   - the dispatcher does not double-write the discriminator (the variant owns the byte via
 *     its `flags: FlagByte` field, which is FIRST in the constructor),
 *   - the encoded stream starts with the exact wire byte we constructed,
 *   - the decoded `flags.lowNibble` matches the per-instance data we packed in,
 *   - the round-trip preserves the full payload.
 */
class RangeRoundTripTest {
    @Test
    fun everyByteInRangeRoundTrips() {
        for (n in 0..0x0F) {
            val wireByte = (0x30 or n).toByte()
            val original: RangeFrame =
                RangeFrame.Framed(
                    flags = FlagByte((0x30 or n).toUByte()),
                    payload = (0xAA00 or n).toUShort(),
                )

            // Encode and check the first byte is the exact composed wire byte.
            val buffer = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
            RangeFrameCodec.encode(buffer, original)
            assertEquals(
                wireByte,
                buffer[0],
                "low nibble $n: encoder must emit wire byte ${wireByte.toInt() and 0xFF}",
            )

            // Round-trip and verify the variant + extracted low nibble.
            val decoded = RangeFrameCodec.testRoundTrip(original)
            assertTrue(
                decoded is RangeFrame.Framed,
                "low nibble $n: round-trip should yield Framed, got $decoded",
            )
            assertEquals(original, decoded)
            assertEquals(n, decoded.flags.lowNibble)
            assertEquals((0xAA00 or n).toUShort(), decoded.payload)
        }
    }

    @Test
    fun typeIdExtractionMatchesRange() {
        // Sanity: every byte in 0x30..0x3F has typeId == 3.
        for (n in 0..0x0F) {
            val flag = FlagByte((0x30 or n).toUByte())
            assertEquals(3, flag.typeId, "wire byte ${0x30 or n}: typeId should be 3")
            assertEquals(n, flag.lowNibble)
        }
    }
}
