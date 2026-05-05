package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.UseCodec

/**
 * Stage G slice 8 doctrine vector — full MQTT v3.1.1 SUBACK packet
 * per §3.9.
 *
 * Wire layout per MQTT-3.1.1 §3.9.1:
 *
 * ```text
 *   +--------+
 *   | 9 0    |   fixed-header byte: type=9 << 4 | flags=0 = 0x90
 *   +--------+
 *   | var-int|   remaining length (1–4 bytes per §2.2.3)
 *   +--------+--------+
 *   |  packet identifier (UShort BE)  |
 *   +--------+--------+
 *   | rc 1   | rc 2   | … | rc N |   return codes (each 1 byte)
 *   +--------+--------+
 * ```
 *
 * The `@RemainingLength` field reads/writes the var-int AND sets
 * the buffer's limit on decode to bound the subsequent fields. The
 * `@RemainingBytes` list naturally consumes the bounded region.
 *
 * Slice 8 lets us model a complete MQTT packet as a single
 * `@ProtocolMessage` data class — no wrapping required (compare
 * the slice 7b `MqttSubAckBody` which modeled only the post-fixed-
 * header bytes and required external limit-setting).
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class MqttSubAck(
    val header: MqttFixedHeader,
    @UseCodec(MqttRemainingLengthCodec::class) val remainingLength: UInt,
    val packetIdentifier: UShort,
    @RemainingBytes val returnCodes: List<UByte>,
)
