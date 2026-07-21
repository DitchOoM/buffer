package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * Hostile-length probe — `@LengthFrom("siblingField")` whose carrier is a
 * full-width `UInt` (4 wire bytes), unlike [RemoteHeader] (`UShort`, max
 * 65535) and the TLS / HTTP/2 fixtures (`@WireBytes(3)`, max 16777215).
 *
 * A full-width carrier is the only shape where a wire-supplied length can
 * reach `Int.MAX_VALUE` and overflow `peekFrameSize`'s running offset
 * (`__offset + payloadBytes`). Non-adjacent carrier (`flags` separates it
 * from the body) so the fixture does not trip R1's adjacent-`@LengthFrom`
 * migration suggestion.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class WideLengthFrame(
    val length: UInt,
    val flags: UByte,
    @LengthFrom("length") val payload: String,
)
