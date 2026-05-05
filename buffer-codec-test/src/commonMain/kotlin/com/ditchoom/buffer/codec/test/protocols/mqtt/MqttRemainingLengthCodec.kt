package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.BoundingLengthCodec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize

/**
 * Phase I.1 step 7 — reference user codec for MQTT v3.1.1 §2.2.3
 * Remaining Length encoding (7 data bits + continuation bit per byte,
 * LSB-first, max 4 bytes).
 *
 * Implements [BoundingLengthCodec] so the processor wraps subsequent
 * fields in the slice 10f outer-limit-capture try/finally pattern,
 * driven by interface inspection rather than the legacy
 * `@RemainingLength` annotation. Produces byte-exact wire output
 * matching the slice 8 `appendDecodeRemainingLength` /
 * `appendEncodeRemainingLength` emit.
 *
 * Lives with the MQTT fixtures (not in `:buffer-codec`) because it's
 * MQTT-specific. The design doc explicitly rejects a
 * `:buffer-codec-stdlib` of common length codecs as premature
 * bundling — each consumer authors the codec it needs.
 */
object MqttRemainingLengthCodec : BoundingLengthCodec<UInt> {
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
            fieldPath = "MqttRemainingLength",
            bufferPosition = buffer.position(),
            expected = "continuation bit clear within 4 bytes",
            actual = "5th continuation byte (malformed per MQTT v3.1.1 §2.2.3)",
        )
    }

    override fun encode(
        buffer: WriteBuffer,
        value: UInt,
        context: EncodeContext,
    ) {
        require(value <= MAX_VALUE) {
            "MQTT remaining length must be <= $MAX_VALUE; got $value"
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

    override fun applyBound(
        buffer: ReadBuffer,
        decodedValue: UInt,
    ) {
        buffer.setLimit(buffer.position() + decodedValue.toInt())
    }
}
