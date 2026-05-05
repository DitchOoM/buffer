package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * MQTT v5.0 §3.8.3 Subscription — a single entry in the SUBSCRIBE
 * topic-filter list. Wire layout: `<topic LP> <opts (UByte)>`.
 *
 * The subscription-options byte packs four fields per §3.8.3.1: QoS
 * (bits 0-1), No Local (bit 2), Retain As Published (bit 3), Retain
 * Handling (bits 4-5), reserved (bits 6-7). Modeled as a raw `UByte`
 * here — typed value-class decomposition is a follow-on once a vector
 * needs to gate on individual bits.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class V5Subscription(
    @LengthPrefixed val topicFilter: String,
    val subscriptionOptions: UByte,
)
