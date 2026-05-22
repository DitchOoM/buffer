package com.ditchoom.buffer.codec.test.protocols.mqttv5.authrc

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import kotlin.jvm.JvmInline

/** MQTT v5 AUTH reason code (§3.15.2.1). */
@JvmInline
@ProtocolMessage
value class V5AuthReasonCodeRaw(
    val raw: UByte,
) {
    @DispatchValue
    val id: Int get() = raw.toInt()
}

@DispatchOn(V5AuthReasonCodeRaw::class)
@ProtocolMessage
sealed interface V5AuthReasonCode {
    @PacketType(value = 0x00)
    @ProtocolMessage
    data class Success(
        val id: V5AuthReasonCodeRaw = V5AuthReasonCodeRaw(0x00u),
    ) : V5AuthReasonCode

    @PacketType(value = 0x18)
    @ProtocolMessage
    data class ContinueAuthentication(
        val id: V5AuthReasonCodeRaw = V5AuthReasonCodeRaw(0x18u),
    ) : V5AuthReasonCode

    @PacketType(value = 0x19)
    @ProtocolMessage
    data class ReAuthenticate(
        val id: V5AuthReasonCodeRaw = V5AuthReasonCodeRaw(0x19u),
    ) : V5AuthReasonCode
}
