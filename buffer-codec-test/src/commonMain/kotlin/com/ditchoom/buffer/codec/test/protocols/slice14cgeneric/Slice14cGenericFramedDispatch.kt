package com.ditchoom.buffer.codec.test.protocols.slice14cgeneric

import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.FramedBy
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.codec.test.protocols.slice14c.Slice14cTinyHeader

/**
 * Phase J.M.5 slice 14c — generic-payload `@FramedBy` probe.
 *
 * The non-generic 14c-prep probe ([com.ditchoom.buffer.codec.test.protocols.slice14c.Slice14cFramedDispatch])
 * pinned down the dispatcher-integration emit for sealed parents without
 * a payload type parameter. This probe extends that coverage to the
 * generic shape: a sealed parent `<out P : Payload>` carrying `@FramedBy`
 * inherited by both a non-generic variant ([Headered]) and a generic
 * variant ([WithPayload]) with a constructor-injected payload codec
 * (the slice-10b shape). Lands the emit changes that the v3/v5
 * substitution leans on, isolated from the 28 MQTT fixtures so a
 * regression in the generic-dispatcher / generic-variant framed paths
 * surfaces here as a focused failure.
 *
 * Wire layout this probe pins down:
 *
 * ```text
 * Headered(header = 0x10, a = 0x42, b = 0xABCD):
 *   10                  fixed header (after-field)
 *   03                  remaining-length VBI (= 1 + 2 body bytes)
 *   42 AB CD            body
 *
 * WithPayload<TextPayload>(header = 0x20, topic = "t", payload = "hi"):
 *   20                  fixed header
 *   05                  remaining-length VBI (= 2 topic-LP + 1 topic + 2 payload)
 *   00 01               LengthPrefixed topic length
 *   74                  "t"
 *   68 69               "hi"
 *
 * WithPayload<TextPayload>(header = 0x20, topic = "t", payload = "x".repeat(200)):
 *   20                  fixed header
 *   CB 01               remaining-length VBI (= 203 in 2 bytes)
 *   00 01               LengthPrefixed topic length
 *   74                  "t"
 *   78 …                200 ASCII 'x' bytes
 * ```
 *
 * The 200-byte payload forces the VBI prefix to extend to 2 bytes,
 * exercising the same right-flush behaviour the non-generic probe
 * pinned down — but through the generic emit path.
 */
@DispatchOn(Slice14cTinyHeader::class)
@FramedBy(MqttRemainingLengthCodec::class, after = "header")
@ProtocolMessage
sealed interface Slice14cGenericFramedDispatch<out P : Payload> {
    @ProtocolMessage
    @PacketType(value = 1, wire = 0x10)
    data class Headered(
        val header: Slice14cTinyHeader,
        val a: UByte,
        val b: UShort,
    ) : Slice14cGenericFramedDispatch<Nothing>

    @ProtocolMessage
    @PacketType(value = 2, wire = 0x20)
    data class WithPayload<P : Payload>(
        val header: Slice14cTinyHeader,
        @LengthPrefixed val topic: String,
        @RemainingBytes val payload: P,
    ) : Slice14cGenericFramedDispatch<P>
}
