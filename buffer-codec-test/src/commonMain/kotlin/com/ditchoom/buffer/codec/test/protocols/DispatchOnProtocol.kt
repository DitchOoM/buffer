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
value class FixedHeaderByte(
    val raw: UByte,
) {
    @DispatchValue
    val packetType: Int get() = raw.toUInt().shr(4).toInt()

    val flags: Int get() = raw.toInt() and 0x0F
}

/**
 * Sealed protocol dispatched by the top 4 bits of the first byte,
 * matching MQTT's fixed header format.
 *
 * [PacketType.value] is the extracted packet type (1, 2, 4) — matched during decode.
 * [PacketType.wire] is the raw byte written during encode (0x10, 0x20, 0x40).
 */
@DispatchOn(FixedHeaderByte::class)
@ProtocolMessage
sealed interface DispatchOnPacket {
    /** Packet type 1 (CONNECT): wire byte 0x10 */
    @PacketType(value = 1, wire = 0x10)
    @ProtocolMessage
    data class TypeConnect(
        val protocolLevel: UByte,
        val keepAlive: UShort,
    ) : DispatchOnPacket

    /** Packet type 2 (CONNACK): wire byte 0x20 */
    @PacketType(value = 2, wire = 0x20)
    @ProtocolMessage
    data class TypeConnAck(
        val sessionPresent: UByte,
        val returnCode: UByte,
    ) : DispatchOnPacket

    /** Packet type 4 (PUBACK): wire byte 0x40 */
    @PacketType(value = 4, wire = 0x40)
    @ProtocolMessage
    @JvmInline
    value class TypePubAck(
        val packetId: UShort,
    ) : DispatchOnPacket
}
