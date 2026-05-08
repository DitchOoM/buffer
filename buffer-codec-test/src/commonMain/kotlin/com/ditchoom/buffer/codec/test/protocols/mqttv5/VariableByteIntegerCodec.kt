package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize

/**
 * Non-bounding VBI codec for v5
 * SubscriptionIdentifier (§2.2.2.2 + §3.8.2.1.2). Same wire shape as
 * [com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec]
 * (7-bit-per-byte continuation, LSB-first, max 4 bytes), but
 * implements plain [Codec] instead of `BoundingLengthCodec`.
 *
 * The distinction matters: when a `@UseCodec` field carries a
 * `BoundingLengthCodec`, the emitter wraps subsequent fields in the
 * outer-limit-restore try/finally template — the decoded
 * value is treated as a remaining-length narrowing the buffer for
 * the rest of the message. SubscriptionIdentifier is just a typed
 * UInt body, no buffer narrowing — plain `Codec<UInt>` skips the
 * `applyBound` wiring.
 *
 * Routes through the bare-`@UseCodec val: <scalar>` analyzer path
 * . The generated emit is `val value = Codec.decode(...)`
 * with no `__OuterLimit` capture or `applyBound` call.
 */
object VariableByteIntegerCodec : Codec<UInt> {
    private const val MAX_VALUE: UInt = 0x0FFF_FFFFu

    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): UInt {
        var value = 0u
        var multiplier = 1u
        repeat(4) {
            val encoded = buffer.readUnsignedByte().toUInt()
            value += (encoded and 0x7Fu) * multiplier
            if ((encoded and 0x80u) == 0u) return value
            multiplier *= 128u
        }
        throw DecodeException(
            fieldPath = "VariableByteInteger",
            bufferPosition = buffer.position(),
            expected = "continuation bit clear within 4 bytes",
            actual = "5th continuation byte (malformed per MQTT v5 §2.2.2.2)",
        )
    }

    override fun encode(
        buffer: WriteBuffer,
        value: UInt,
        context: EncodeContext,
    ) {
        require(value <= MAX_VALUE) {
            "Variable-byte-integer must be <= $MAX_VALUE; got $value"
        }
        var remaining = value
        do {
            var encodedByte = remaining and 0x7Fu
            remaining = remaining shr 7
            if (remaining > 0u) encodedByte = encodedByte or 0x80u
            buffer.writeByte(encodedByte.toByte())
        } while (remaining > 0u)
    }

    override fun wireSize(
        value: UInt,
        context: EncodeContext,
    ): WireSize =
        WireSize.Exact(
            when {
                value < 128u -> 1
                value < 16_384u -> 2
                value < 2_097_152u -> 3
                else -> 4
            },
        )
}
