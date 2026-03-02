package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.WhenTrue
import kotlin.jvm.JvmInline

@JvmInline
value class ConnectFlags(
    val raw: UByte,
) {
    val cleanSession: Boolean get() = (raw.toInt() shr 1) and 1 == 1
    val willFlag: Boolean get() = (raw.toInt() shr 2) and 1 == 1
    val willQos: Int get() = (raw.toInt() shr 3) and 3
    val willRetain: Boolean get() = (raw.toInt() shr 5) and 1 == 1
    val passwordFlag: Boolean get() = (raw.toInt() shr 6) and 1 == 1
    val usernameFlag: Boolean get() = (raw.toInt() shr 7) and 1 == 1
}

@ProtocolMessage
data class MqttConnect<@Payload WP, @Payload PP>(
    @LengthPrefixed val protocolName: String,
    val protocolLevel: UByte,
    val flags: ConnectFlags,
    val keepAlive: UShort,
    @LengthPrefixed val clientId: String,
    @WhenTrue("flags.willFlag") @LengthPrefixed val willTopic: String? = null,
    @WhenTrue("flags.willFlag") @LengthPrefixed val willPayload: WP? = null,
    @WhenTrue("flags.usernameFlag") @LengthPrefixed val username: String? = null,
    @WhenTrue("flags.passwordFlag") @LengthPrefixed val password: PP? = null,
)
