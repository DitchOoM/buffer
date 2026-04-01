package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * MQTT-style fixed header: top 4 bits = packet type, bottom 4 bits = flags.
 * Demonstrates @DispatchOn with bit-packed discriminator extraction.
 */
@JvmInline
@ProtocolMessage
value class FixedHeaderByte(val raw: UByte) {
    @DispatchValue
    val packetType: Int get() = raw.toUInt().shr(4).toInt()

    val flags: Int get() = raw.toInt() and 0x0F
}

/**
 * Sealed protocol dispatched by the top 4 bits of the first byte,
 * matching MQTT's fixed header format.
 *
 * @PacketType values are the EXTRACTED packet type (1, 2, 4) — the value
 * returned by [FixedHeaderByte.packetType], not the raw header byte.
 *
 * Note: encode writes the raw @PacketType value as a byte (0x01, 0x02, 0x04),
 * not the MQTT-spec header byte (0x10, 0x20, 0x40). Full encode requires
 * the inverse of the dispatch extraction, which is protocol-specific.
 * Use sub-codecs directly for spec-compliant encode.
 */
@DispatchOn(FixedHeaderByte::class)
@ProtocolMessage
sealed interface DispatchOnPacket {
    /** Packet type 1: like MQTT CONNECT */
    @PacketType(1)
    @ProtocolMessage
    data class TypeConnect(
        val protocolLevel: UByte,
        val keepAlive: UShort,
    ) : DispatchOnPacket

    /** Packet type 2: like MQTT CONNACK */
    @PacketType(2)
    @ProtocolMessage
    data class TypeConnAck(
        val sessionPresent: UByte,
        val returnCode: UByte,
    ) : DispatchOnPacket

    /** Packet type 4: like MQTT PUBACK */
    @PacketType(4)
    @ProtocolMessage
    @JvmInline
    value class TypePubAck(
        val packetId: UShort,
    ) : DispatchOnPacket
}
