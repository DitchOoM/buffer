package com.ditchoom.buffer.codec.test.protocols.http2

import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes

/**
 * HTTP/2 DATA frame per RFC 7540 §6.1, generic-bounded over the
 * payload type. Wire layout:
 *
 * ```text
 *   +--------+--------+--------+--------+
 *   | length (24 bits, BE)     |  type=0|   header (Http2LengthAndType)
 *   +--------+--------+--------+--------+
 *   | flags  |
 *   +--------+--------+--------+--------+
 *   |R|              streamId (31 bits, BE)         |
 *   +--------+--------+--------+--------+
 *   | data (length bytes, decoded by payloadCodec)               |
 *   +--------+--------+--------+--------+
 * ```
 *
 * Stage H slice 10b doctrine vector — generic emission against a
 * non-MQTT protocol. Standalone for slice 10b; the sealed-parent
 * integration with `Http2Frame<out P : Payload>` (the doctrinal
 * answer to "how do generics compose with sealed dispatch?")
 * lands in slice 10d alongside the aggregator pattern.
 *
 * Slice 10b narrow:
 *   - DATA frame's PADDED flag (bit 0x08) is ignored; the optional
 *     padding length byte + trailing pad bytes are deferred. Slice
 *     10b assumes no padding (matches the simplest §6.1 wire shape).
 *   - END_STREAM flag (bit 0x01) is preserved on the wire (it's
 *     just a flag bit in the existing `flags: UByte` field) but
 *     carries no codec semantics.
 *   - The body's byte count is determined by the buffer's outer
 *     limit (slice 10a/10b shape) — NOT by `header.length`. A
 *     consumer wraps the codec with a `setLimit(header.length)`
 *     before delegating; slice 10d's outer dispatcher will own this.
 *
 * Same generic emit shape as `MqttPublishV3<P : Payload>` — confirms
 * the path isn't accidentally MQTT-specific.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class Http2DataFrame<P : Payload>(
    val header: Http2LengthAndType,
    val flags: UByte,
    val streamId: Http2StreamId,
    @RemainingBytes val payload: P,
)
