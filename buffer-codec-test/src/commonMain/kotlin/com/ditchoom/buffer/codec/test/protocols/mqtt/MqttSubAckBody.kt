package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes

/**
 * MQTT v3.1.1 SUBACK packet body per §3.9.
 *
 * Wire layout (after the MQTT fixed header, which carries packet
 * type 9 + flags 0 + variable-length-integer remaining length):
 *
 * ```text
 *   +--------+--------+
 *   |   packet identifier (UShort BE)   |
 *   +--------+-------+--------+--------+
 *   | return code 1  | return code 2  | … | return code N |
 *   +--------+-------+--------+--------+
 * ```
 *
 * Each return code is one byte (per §3.9.3):
 *   0x00 Success — Maximum QoS 0
 *   0x01 Success — Maximum QoS 1
 *   0x02 Success — Maximum QoS 2
 *   0x80 Failure
 *
 * Stage G slice 7b doctrine vector — exercises `@RemainingBytes`
 * on `List<UByte>`. The list reads until the buffer's limit is
 * reached; the caller must set the limit externally based on the
 * MQTT fixed header's remaining-length variable-length integer
 * (parsed at a higher layer; full MQTT fixed-header support is a
 * future slice).
 *
 * Models the BODY only — not a complete SUBACK packet — for the
 * same reason `MqttConnect` (slice 5b) modeled only the variable
 * header + payload. Once variable-length integer support lands, a
 * real `MqttSubAck` can wrap this body under `MqttPacket`'s
 * dispatcher.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class MqttSubAckBody(
    val packetIdentifier: UShort,
    @RemainingBytes val returnCodes: List<UByte>,
)
