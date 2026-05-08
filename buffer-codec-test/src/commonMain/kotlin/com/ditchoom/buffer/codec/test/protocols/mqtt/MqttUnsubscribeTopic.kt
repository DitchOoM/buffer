package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * MQTT v3.1.1 §3.10.3 UNSUBSCRIBE payload element — a single
 * length-prefixed UTF-8 topic name. Unlike SUBSCRIBE, UNSUBSCRIBE
 * carries no per-topic QoS byte; the on-wire shape is just the LP
 * topic.
 *
 * Modeled as a `@ProtocolMessage data class` wrapping the LP string
 * because the emitter slice
 * (`@RemainingBytes List<@ProtocolMessage T>`) accepts only
 * data-class elements — bare `List<@LengthPrefixed String>` is not
 * yet supported. The wrapper costs nothing on the wire (the data
 * class has no fixed overhead) and unblocks the UNSUBSCRIBE wire
 * model without a new emitter capability.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class MqttUnsubscribeTopic(
    @LengthPrefixed val name: String,
)
