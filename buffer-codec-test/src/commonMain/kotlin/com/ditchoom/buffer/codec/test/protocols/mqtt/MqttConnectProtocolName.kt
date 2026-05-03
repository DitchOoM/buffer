package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/**
 * MQTT v3.1.1 §3.1.2.1 protocol-name field. The wire shape is
 * `0x00 0x04 'M' 'Q' 'T' 'T'` — a 2-byte big-endian length prefix
 * followed by the UTF-8 body. Modeled as a `@JvmInline value class`
 * because there is exactly one field; the round-trip verification
 * covers the byte-exact spec encoding.
 *
 * Stage C real-spec fixture. Cross-coverage: confirms Stage B's
 * value-class top-level path composes with Stage C's String emission.
 */
@JvmInline
@ProtocolMessage
value class MqttConnectProtocolName(
    @LengthPrefixed val name: String,
)
