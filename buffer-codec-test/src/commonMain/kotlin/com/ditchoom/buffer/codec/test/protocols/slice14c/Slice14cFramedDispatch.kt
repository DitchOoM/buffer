package com.ditchoom.buffer.codec.test.protocols.slice14c

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.FramedBy
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import kotlin.jvm.JvmInline

/**
 * Phase J.M.5 slice 14c-prep — sealed-parent + `@FramedBy(after = "header")`
 * probe. Exercises the emitter prep work that the v3/v5 substitution
 * (slice 14c proper) leans on, isolated from the 28 MQTT fixtures so a
 * regression in the inherited-detection / after-X / peekFrameSize /
 * dispatcher-integration emit shows up as a focused failure here rather
 * than a wave of round-trip breaks.
 *
 * Wire layout the probe pins down:
 *
 * ```text
 * A(header = 0x10, a = 0x42, b = 0xABCD):
 *   10                  fixed header (after-field, 1 byte)
 *   03                  remaining-length VBI prefix (= 1 + 2 body bytes)
 *   42 AB CD            body
 *
 * B(header = 0x20, message = "hi"):
 *   20                  fixed header
 *   04                  remaining-length VBI prefix (= 2 length-prefix + 2 body)
 *   00 02               LengthPrefixed string length
 *   68 69               "hi"
 *
 * B(header = 0x20, message = "x".repeat(200)):
 *   20                  fixed header
 *   CA 01               remaining-length VBI prefix (202 = 0xCA in two bytes)
 *   00 C8               LengthPrefixed string length (200)
 *   78 …                200 ASCII 'x' bytes
 * ```
 *
 * The 200-character body forces the VBI prefix to extend to 2 bytes,
 * confirming the slicing scheme right-flushes a wider prefix into the
 * slack region without disturbing body bytes.
 */
@JvmInline
@ProtocolMessage
value class Slice14cTinyHeader(
    val raw: UByte,
) {
    @DispatchValue
    val packetType: Int get() = raw.toUInt().shr(4).toInt()
}

@DispatchOn(Slice14cTinyHeader::class)
@FramedBy(MqttRemainingLengthCodec::class, after = "header")
@ProtocolMessage
sealed interface Slice14cFramedDispatch {
    @ProtocolMessage
    @PacketType(value = 1, wire = 0x10)
    data class A(
        val header: Slice14cTinyHeader,
        val a: UByte,
        val b: UShort,
    ) : Slice14cFramedDispatch

    @ProtocolMessage
    @PacketType(value = 2, wire = 0x20)
    data class B(
        val header: Slice14cTinyHeader,
        @LengthPrefixed val message: String,
    ) : Slice14cFramedDispatch
}
