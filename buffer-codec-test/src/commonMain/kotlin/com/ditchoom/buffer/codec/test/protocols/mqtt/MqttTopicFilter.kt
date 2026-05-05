package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * MQTT v3.1.1 §3.8.3 SUBSCRIBE payload element — a length-prefixed
 * UTF-8 topic filter followed by the requested QoS byte.
 *
 * The wire layout is `<2-byte LP><filter bytes><qos>`, repeated for
 * each filter the SUBSCRIBE carries. SUBSCRIBE's variant wraps the
 * list with `@RemainingBytes List<MqttTopicFilter>` — bound by the
 * outer remaining-length var-int — to ride the J.M.0 emitter slice
 * (`@RemainingBytes List<@ProtocolMessage T>`).
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class MqttTopicFilter(
    @LengthPrefixed val filter: String,
    val qos: UByte,
)
