package com.ditchoom.buffer.protocol.mqtt

import com.ditchoom.buffer.ReadBuffer

/**
 * MQTT packet model using sealed interfaces for exhaustive matching.
 *
 * Implements MQTT 3.1.1 and MQTT 5.0 packet structure.
 */
sealed interface MqttPacket {
    val packetType: MqttPacketType
    val remainingLength: Int
}

/**
 * MQTT packet types (MQTT Control Packet type).
 */
sealed interface MqttPacketType {
    val value: Int

    data object Connect : MqttPacketType {
        override val value = 1
    }

    data object ConnAck : MqttPacketType {
        override val value = 2
    }

    data object Publish : MqttPacketType {
        override val value = 3
    }

    data object PubAck : MqttPacketType {
        override val value = 4
    }

    data object PubRec : MqttPacketType {
        override val value = 5
    }

    data object PubRel : MqttPacketType {
        override val value = 6
    }

    data object PubComp : MqttPacketType {
        override val value = 7
    }

    data object Subscribe : MqttPacketType {
        override val value = 8
    }

    data object SubAck : MqttPacketType {
        override val value = 9
    }

    data object Unsubscribe : MqttPacketType {
        override val value = 10
    }

    data object UnsubAck : MqttPacketType {
        override val value = 11
    }

    data object PingReq : MqttPacketType {
        override val value = 12
    }

    data object PingResp : MqttPacketType {
        override val value = 13
    }

    data object Disconnect : MqttPacketType {
        override val value = 14
    }

    data object Auth : MqttPacketType {
        override val value = 15
    }

    data class Reserved(
        override val value: Int,
    ) : MqttPacketType

    companion object {
        fun fromInt(value: Int): MqttPacketType =
            when (value) {
                1 -> Connect
                2 -> ConnAck
                3 -> Publish
                4 -> PubAck
                5 -> PubRec
                6 -> PubRel
                7 -> PubComp
                8 -> Subscribe
                9 -> SubAck
                10 -> Unsubscribe
                11 -> UnsubAck
                12 -> PingReq
                13 -> PingResp
                14 -> Disconnect
                15 -> Auth
                else -> Reserved(value)
            }
    }
}

/**
 * MQTT CONNECT packet (Client requests connection).
 */
data class MqttConnect(
    override val remainingLength: Int,
    val protocolName: String,
    val protocolLevel: Int,
    val cleanSession: Boolean,
    val willFlag: Boolean,
    val willQos: MqttQos,
    val willRetain: Boolean,
    val passwordFlag: Boolean,
    val usernameFlag: Boolean,
    val keepAliveSeconds: Int,
    val clientId: String,
    val willTopic: String?,
    val willPayload: MqttPayload?,
    val username: String?,
    val password: ReadBuffer?,
) : MqttPacket {
    override val packetType = MqttPacketType.Connect

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MqttConnect) return false
        return protocolName == other.protocolName &&
            protocolLevel == other.protocolLevel &&
            clientId == other.clientId &&
            cleanSession == other.cleanSession
    }

    override fun hashCode(): Int {
        var result = protocolName.hashCode()
        result = 31 * result + protocolLevel
        result = 31 * result + clientId.hashCode()
        return result
    }
}

/**
 * MQTT CONNACK packet (Connection acknowledgement).
 */
data class MqttConnAck(
    override val remainingLength: Int,
    val sessionPresent: Boolean,
    val returnCode: MqttConnectReturnCode,
) : MqttPacket {
    override val packetType = MqttPacketType.ConnAck
}

/**
 * MQTT PUBLISH packet.
 */
data class MqttPublish(
    override val remainingLength: Int,
    val dup: Boolean,
    val qos: MqttQos,
    val retain: Boolean,
    val topicName: String,
    val packetId: Int?,
    val payload: MqttPayload,
) : MqttPacket {
    override val packetType = MqttPacketType.Publish
}

/**
 * MQTT PUBACK packet (QoS 1 acknowledgement).
 */
data class MqttPubAck(
    override val remainingLength: Int,
    val packetId: Int,
) : MqttPacket {
    override val packetType = MqttPacketType.PubAck
}

/**
 * MQTT PUBREC packet (QoS 2 - part 1).
 */
data class MqttPubRec(
    override val remainingLength: Int,
    val packetId: Int,
) : MqttPacket {
    override val packetType = MqttPacketType.PubRec
}

/**
 * MQTT PUBREL packet (QoS 2 - part 2).
 */
data class MqttPubRel(
    override val remainingLength: Int,
    val packetId: Int,
) : MqttPacket {
    override val packetType = MqttPacketType.PubRel
}

/**
 * MQTT PUBCOMP packet (QoS 2 - part 3).
 */
data class MqttPubComp(
    override val remainingLength: Int,
    val packetId: Int,
) : MqttPacket {
    override val packetType = MqttPacketType.PubComp
}

/**
 * MQTT SUBSCRIBE packet.
 */
data class MqttSubscribe(
    override val remainingLength: Int,
    val packetId: Int,
    val subscriptions: List<MqttSubscription>,
) : MqttPacket {
    override val packetType = MqttPacketType.Subscribe
}

/**
 * MQTT SUBACK packet.
 */
data class MqttSubAck(
    override val remainingLength: Int,
    val packetId: Int,
    val returnCodes: List<MqttSubAckReturnCode>,
) : MqttPacket {
    override val packetType = MqttPacketType.SubAck
}

/**
 * MQTT UNSUBSCRIBE packet.
 */
data class MqttUnsubscribe(
    override val remainingLength: Int,
    val packetId: Int,
    val topicFilters: List<String>,
) : MqttPacket {
    override val packetType = MqttPacketType.Unsubscribe
}

/**
 * MQTT UNSUBACK packet.
 */
data class MqttUnsubAck(
    override val remainingLength: Int,
    val packetId: Int,
) : MqttPacket {
    override val packetType = MqttPacketType.UnsubAck
}

/**
 * MQTT PINGREQ packet.
 */
data object MqttPingReq : MqttPacket {
    override val packetType = MqttPacketType.PingReq
    override val remainingLength = 0
}

/**
 * MQTT PINGRESP packet.
 */
data object MqttPingResp : MqttPacket {
    override val packetType = MqttPacketType.PingResp
    override val remainingLength = 0
}

/**
 * MQTT DISCONNECT packet.
 */
data class MqttDisconnect(
    override val remainingLength: Int,
    val reasonCode: MqttDisconnectReason?,
) : MqttPacket {
    override val packetType = MqttPacketType.Disconnect
}

/**
 * MQTT Quality of Service levels.
 */
sealed interface MqttQos {
    val value: Int

    data object AtMostOnce : MqttQos {
        override val value = 0
    }

    data object AtLeastOnce : MqttQos {
        override val value = 1
    }

    data object ExactlyOnce : MqttQos {
        override val value = 2
    }

    companion object {
        fun fromInt(value: Int): MqttQos =
            when (value) {
                0 -> AtMostOnce
                1 -> AtLeastOnce
                2 -> ExactlyOnce
                else -> throw IllegalArgumentException("Invalid QoS value: $value")
            }
    }
}

/**
 * MQTT Connect return codes.
 */
sealed interface MqttConnectReturnCode {
    val value: Int
    val description: String

    data object Accepted : MqttConnectReturnCode {
        override val value = 0
        override val description = "Connection Accepted"
    }

    data object UnacceptableProtocolVersion : MqttConnectReturnCode {
        override val value = 1
        override val description = "Unacceptable Protocol Version"
    }

    data object IdentifierRejected : MqttConnectReturnCode {
        override val value = 2
        override val description = "Identifier Rejected"
    }

    data object ServerUnavailable : MqttConnectReturnCode {
        override val value = 3
        override val description = "Server Unavailable"
    }

    data object BadCredentials : MqttConnectReturnCode {
        override val value = 4
        override val description = "Bad Username or Password"
    }

    data object NotAuthorized : MqttConnectReturnCode {
        override val value = 5
        override val description = "Not Authorized"
    }

    data class Reserved(
        override val value: Int,
    ) : MqttConnectReturnCode {
        override val description = "Reserved ($value)"
    }

    companion object {
        fun fromInt(value: Int): MqttConnectReturnCode =
            when (value) {
                0 -> Accepted
                1 -> UnacceptableProtocolVersion
                2 -> IdentifierRejected
                3 -> ServerUnavailable
                4 -> BadCredentials
                5 -> NotAuthorized
                else -> Reserved(value)
            }
    }
}

/**
 * MQTT SUBACK return codes.
 */
sealed interface MqttSubAckReturnCode {
    val value: Int

    data object SuccessQos0 : MqttSubAckReturnCode {
        override val value = 0x00
    }

    data object SuccessQos1 : MqttSubAckReturnCode {
        override val value = 0x01
    }

    data object SuccessQos2 : MqttSubAckReturnCode {
        override val value = 0x02
    }

    data object Failure : MqttSubAckReturnCode {
        override val value = 0x80
    }

    data class Unknown(
        override val value: Int,
    ) : MqttSubAckReturnCode

    companion object {
        fun fromInt(value: Int): MqttSubAckReturnCode =
            when (value) {
                0x00 -> SuccessQos0
                0x01 -> SuccessQos1
                0x02 -> SuccessQos2
                0x80 -> Failure
                else -> Unknown(value)
            }
    }
}

/**
 * MQTT 5.0 Disconnect reason codes.
 */
sealed interface MqttDisconnectReason {
    val value: Int
    val description: String

    data object NormalDisconnection : MqttDisconnectReason {
        override val value = 0x00
        override val description = "Normal disconnection"
    }

    data object DisconnectWithWillMessage : MqttDisconnectReason {
        override val value = 0x04
        override val description = "Disconnect with Will Message"
    }

    data object UnspecifiedError : MqttDisconnectReason {
        override val value = 0x80
        override val description = "Unspecified error"
    }

    data object MalformedPacket : MqttDisconnectReason {
        override val value = 0x81
        override val description = "Malformed Packet"
    }

    data object ProtocolError : MqttDisconnectReason {
        override val value = 0x82
        override val description = "Protocol Error"
    }

    data object ImplementationSpecificError : MqttDisconnectReason {
        override val value = 0x83
        override val description = "Implementation specific error"
    }

    data object NotAuthorized : MqttDisconnectReason {
        override val value = 0x87
        override val description = "Not authorized"
    }

    data object ServerBusy : MqttDisconnectReason {
        override val value = 0x89
        override val description = "Server busy"
    }

    data object ServerShuttingDown : MqttDisconnectReason {
        override val value = 0x8B
        override val description = "Server shutting down"
    }

    data object KeepAliveTimeout : MqttDisconnectReason {
        override val value = 0x8D
        override val description = "Keep Alive timeout"
    }

    data object SessionTakenOver : MqttDisconnectReason {
        override val value = 0x8E
        override val description = "Session taken over"
    }

    data object TopicFilterInvalid : MqttDisconnectReason {
        override val value = 0x8F
        override val description = "Topic Filter invalid"
    }

    data object TopicNameInvalid : MqttDisconnectReason {
        override val value = 0x90
        override val description = "Topic Name invalid"
    }

    data object ReceiveMaximumExceeded : MqttDisconnectReason {
        override val value = 0x93
        override val description = "Receive Maximum exceeded"
    }

    data object TopicAliasInvalid : MqttDisconnectReason {
        override val value = 0x94
        override val description = "Topic Alias invalid"
    }

    data object PacketTooLarge : MqttDisconnectReason {
        override val value = 0x95
        override val description = "Packet too large"
    }

    data object MessageRateTooHigh : MqttDisconnectReason {
        override val value = 0x96
        override val description = "Message rate too high"
    }

    data object QuotaExceeded : MqttDisconnectReason {
        override val value = 0x97
        override val description = "Quota exceeded"
    }

    data object AdministrativeAction : MqttDisconnectReason {
        override val value = 0x98
        override val description = "Administrative action"
    }

    data object PayloadFormatInvalid : MqttDisconnectReason {
        override val value = 0x99
        override val description = "Payload format invalid"
    }

    data class Unknown(
        override val value: Int,
    ) : MqttDisconnectReason {
        override val description = "Unknown ($value)"
    }

    companion object {
        fun fromInt(value: Int): MqttDisconnectReason =
            when (value) {
                0x00 -> NormalDisconnection
                0x04 -> DisconnectWithWillMessage
                0x80 -> UnspecifiedError
                0x81 -> MalformedPacket
                0x82 -> ProtocolError
                0x83 -> ImplementationSpecificError
                0x87 -> NotAuthorized
                0x89 -> ServerBusy
                0x8B -> ServerShuttingDown
                0x8D -> KeepAliveTimeout
                0x8E -> SessionTakenOver
                0x8F -> TopicFilterInvalid
                0x90 -> TopicNameInvalid
                0x93 -> ReceiveMaximumExceeded
                0x94 -> TopicAliasInvalid
                0x95 -> PacketTooLarge
                0x96 -> MessageRateTooHigh
                0x97 -> QuotaExceeded
                0x98 -> AdministrativeAction
                0x99 -> PayloadFormatInvalid
                else -> Unknown(value)
            }
    }
}

/**
 * MQTT subscription request.
 */
data class MqttSubscription(
    val topicFilter: String,
    val qos: MqttQos,
)

/**
 * MQTT payload using zero-copy buffer operations.
 */
sealed interface MqttPayload {
    /**
     * Returns the payload as a ReadBuffer for zero-copy access.
     */
    fun asBuffer(): ReadBuffer

    /**
     * Returns the payload as a string (UTF-8).
     */
    fun text(): String {
        val buffer = asBuffer()
        return buffer.readString(buffer.remaining())
    }

    val length: Int
    val isCompressed: Boolean

    data object Empty : MqttPayload {
        override fun asBuffer(): ReadBuffer = ReadBuffer.EMPTY_BUFFER

        override val length = 0
        override val isCompressed = false
    }

    /**
     * Payload backed by a buffer (zero-copy).
     */
    data class Buffered(
        private val buffer: ReadBuffer,
        override val length: Int,
        override val isCompressed: Boolean = false,
    ) : MqttPayload {
        override fun asBuffer(): ReadBuffer = buffer
    }

    /**
     * Compressed payload that decompresses on access.
     */
    data class Compressed(
        private val compressedBuffer: ReadBuffer,
        private val decompressor: (ReadBuffer) -> ReadBuffer,
    ) : MqttPayload {
        override val isCompressed = true
        override val length = compressedBuffer.remaining()

        private var decompressedCache: ReadBuffer? = null

        override fun asBuffer(): ReadBuffer =
            decompressedCache ?: decompressor(compressedBuffer).also {
                decompressedCache = it
            }
    }
}
