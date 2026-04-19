package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.test.protocols.CommandPayload
import com.ditchoom.buffer.codec.test.protocols.FramedCommand
import com.ditchoom.buffer.codec.test.protocols.FramedCommandCodec
import com.ditchoom.buffer.codec.test.protocols.LengthPrefixedCommand
import com.ditchoom.buffer.codec.test.protocols.LengthPrefixedCommandCodec
import com.ditchoom.buffer.codec.test.protocols.PayloadWithChecksum
import com.ditchoom.buffer.codec.test.protocols.PayloadWithChecksumCodec
import com.ditchoom.buffer.codec.test.protocols.RemainingBytesCommand
import com.ditchoom.buffer.codec.test.protocols.RemainingBytesCommandCodec
import com.ditchoom.buffer.codec.testRoundTrip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Tests for length annotations on nested `@ProtocolMessage` fields (issue #151 Change 2a).
 *
 * The wire shape is `[outer header][body:length bytes]` where `body` is a `@ProtocolMessage`
 * data class that wraps a variable-size payload plus a trailing checksum. The user computes
 * `length` from `body`'s actual wire size on encode; the processor emits a sliced decode.
 */
class FramedCommandRoundTripTest {
    // Payload type byte (1) + variant body (0 or 3) + trailing UShort checksum (2)
    private fun bodyWireBytes(payload: CommandPayload): Int =
        1 +
            when (payload) {
                is CommandPayload.SetRgbState -> 3
                CommandPayload.GetRgbState, CommandPayload.ResetDevice -> 0
            } + 2

    @Test
    fun framedCommandWithSetRgbRoundTrips() {
        val payload = CommandPayload.SetRgbState(r = 1u, g = 2u, b = 3u)
        val body = PayloadWithChecksum(payload = payload, checksum = 0xBEEFu)
        val bodyLen = bodyWireBytes(payload)
        val original = FramedCommand(counter = 42u, length = bodyLen.toUShort(), body = body)
        val decoded = FramedCommandCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun framedCommandWithGetRgbDataObjectRoundTrips() {
        val payload = CommandPayload.GetRgbState
        val body = PayloadWithChecksum(payload = payload, checksum = 0x1234u)
        val bodyLen = bodyWireBytes(payload)
        val original = FramedCommand(counter = 7u, length = bodyLen.toUShort(), body = body)
        val decoded = FramedCommandCodec.testRoundTrip(original)
        assertSame(CommandPayload.GetRgbState, decoded.body.payload)
        assertEquals(0x1234u.toUShort(), decoded.body.checksum)
    }

    @Test
    fun framedCommandBodyStopsAtLengthBoundary() {
        // Append extra junk after the frame and verify the decoder does NOT consume it.
        val payload = CommandPayload.GetRgbState
        val body = PayloadWithChecksum(payload = payload, checksum = 0xABCDu)
        val bodyLen = bodyWireBytes(payload)
        val original = FramedCommand(counter = 99u, length = bodyLen.toUShort(), body = body)

        val buffer = BufferFactory.Default.allocate(64, ByteOrder.LITTLE_ENDIAN)
        FramedCommandCodec.encode(buffer, original, EncodeContext.Empty)
        // Inject 3 junk bytes after the frame — decoding body via @LengthFrom should ignore them.
        buffer.writeByte(0xDE.toByte())
        buffer.writeByte(0xAD.toByte())
        buffer.writeByte(0xBE.toByte())
        val framePlusJunkEnd = buffer.position()
        buffer.setLimit(framePlusJunkEnd)
        buffer.position(0)

        val decoded = FramedCommandCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
        // After decode, the 3 junk bytes must remain.
        assertEquals(3, buffer.remaining())
    }

    @Test
    fun lengthPrefixedCommandRoundTrips() {
        val payload = CommandPayload.SetRgbState(r = 0x10u, g = 0x20u, b = 0x30u)
        val body = PayloadWithChecksum(payload = payload, checksum = 0xCAFEu)
        val original = LengthPrefixedCommand(counter = 5u, body = body)
        val decoded = LengthPrefixedCommandCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun remainingBytesCommandRoundTrips() {
        val payload = CommandPayload.SetRgbState(r = 0xAAu, g = 0xBBu, b = 0xCCu)
        val body = PayloadWithChecksum(payload = payload, checksum = 0x4321u)
        val original = RemainingBytesCommand(counter = 13u, body = body)
        val decoded = RemainingBytesCommandCodec.testRoundTrip(original)
        assertEquals(original, decoded)
    }

    @Test
    fun payloadWithChecksumRoundTripsStandalone() {
        // Nested wrapper still works as a top-level message (no outer length).
        val original = PayloadWithChecksum(payload = CommandPayload.ResetDevice, checksum = 0x55AAu)
        val decoded = PayloadWithChecksumCodec.testRoundTrip(original)
        assertSame(CommandPayload.ResetDevice, decoded.payload)
        assertEquals(0x55AAu.toUShort(), decoded.checksum)
    }
}
