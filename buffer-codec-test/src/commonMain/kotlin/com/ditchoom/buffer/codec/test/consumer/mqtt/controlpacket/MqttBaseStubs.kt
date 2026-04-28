// PHASE 9 STUB — copied/distilled from mqtt/models-base/src/commonMain/kotlin/com/ditchoom/mqtt/**
// Self-contained surface that the consumer fixture wire shapes (mqtt v4 / v5 / websocket)
// can be typechecked against without dragging in the full mqtt repo. Classes here preserve
// the API shape that the @ProtocolMessage data classes reference; method bodies are trimmed
// to the minimum needed for KSP to discover the wire shapes.
// Deleted in Phase 9 Step 7 once consumer cutover is verified.
package com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.BodyLengthFraming
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.test.consumer.mqtt.MalformedPacketException
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.buffer.codec.test.consumer.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.buffer.readVariableByteInteger
import com.ditchoom.buffer.stream.PeekResult
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.variableByteSizeInt
import com.ditchoom.buffer.writeVariableByteInteger
import kotlin.jvm.JvmInline

// ── MqttFixedHeader (verbatim copy from MqttFixedHeader.kt) ─────────────────

@JvmInline
@ProtocolMessage
value class MqttFixedHeader(
    val raw: UByte,
) {
    init {
        if (packetType == 3 && (raw.toInt() and 0b110) == 0b110) {
            throw MalformedPacketException(
                "PUBLISH QoS = 3 is malformed (both QoS bits set); only QoS 0/1/2 are valid.",
            )
        }
    }

    @DispatchValue
    val packetType: Int get() = (raw.toInt() shr 4) and 0x0F

    val flags: Int get() = raw.toInt() and 0x0F

    val publishDup: Boolean get() = (raw.toInt() shr 3) and 1 == 1
    val publishQos: Int get() = (raw.toInt() shr 1) and 0x3
    val publishRetain: Boolean get() = raw.toInt() and 1 == 1
    val publishHasPacketIdentifier: Boolean get() = publishQos > 0

    companion object : BodyLengthFraming<MqttFixedHeader> {
        override fun peekFrameSize(
            stream: StreamProcessor,
            baseOffset: Int,
        ): PeekResult {
            if (stream.available() < baseOffset + 2) return PeekResult.NeedsMoreData
            var width = 0
            var len = 0
            var multiplier = 1
            while (width < 4) {
                if (stream.available() < baseOffset + 1 + width + 1) return PeekResult.NeedsMoreData
                val byte = stream.peekByte(baseOffset + 1 + width).toInt() and 0xFF
                len += (byte and 0x7F) * multiplier
                multiplier *= 128
                width += 1
                if ((byte and 0x80) == 0) return PeekResult.Size(1 + width + len)
            }
            return PeekResult.NeedsMoreData
        }

        override fun readBodyLength(buffer: ReadBuffer): Int = buffer.readVariableByteInteger()

        override fun writeBodyLength(buffer: WriteBuffer, n: Int) {
            buffer.writeVariableByteInteger(n)
        }

        override fun bodyLengthSize(n: Int): Int = variableByteSizeInt(n)
    }
}

// ── QualityOfService ─────────────────────────────────────────────────────────

enum class QualityOfService(
    val integerValue: Byte,
) {
    AT_MOST_ONCE(0),
    AT_LEAST_ONCE(1),
    EXACTLY_ONCE(2),
    ;

    fun isGreaterThan(otherQos: QualityOfService) = integerValue > otherQos.integerValue

    companion object {
        fun fromBooleans(bit2: Boolean, bit1: Boolean): QualityOfService =
            if (bit2 && !bit1) EXACTLY_ONCE
            else if (!bit2 && bit1) AT_LEAST_ONCE
            else if (!bit2 && !bit1) AT_MOST_ONCE
            else throw MalformedPacketException("Invalid flags 0x03.")
    }
}

// ── TopicName / TopicFilter — minimal stubs preserving fromOrThrow contract ──

class TopicName(val value: String) {
    override fun toString(): String = value
    override fun equals(other: Any?): Boolean = other is TopicName && value == other.value
    override fun hashCode(): Int = value.hashCode()

    companion object {
        fun fromOrThrow(topic: String): TopicName = TopicName(topic)
        fun fromOrNull(topic: String): TopicName? = TopicName(topic)
    }
}

class TopicFilter(val value: String) {
    override fun toString(): String = value
    override fun equals(other: Any?): Boolean = other is TopicFilter && value == other.value
    override fun hashCode(): Int = value.hashCode()

    companion object {
        fun fromOrThrow(topic: String): TopicFilter = TopicFilter(topic)
        fun fromOrNull(topic: String): TopicFilter? = TopicFilter(topic)
    }
}

// ── WillConfig (sealed, preserved API) ───────────────────────────────────────

sealed interface WillConfig {
    data object Disabled : WillConfig

    data class Enabled(
        val topic: TopicName,
        val payload: ReadBuffer,
        val qos: QualityOfService = QualityOfService.AT_MOST_ONCE,
        val retain: Boolean = false,
    ) : WillConfig
}

// ── ControlPacket / ControlPacketFactory (minimal stubs) ─────────────────────

interface ControlPacket {
    val controlPacketValue: Byte
    val direction: DirectionOfFlow
    val flags: Byte get() = 0b0
    val mqttVersion: Byte
    val packetIdentifier: Int get() = NO_PACKET_ID
    val controlPacketFactory: ControlPacketFactory

    fun validate(): Exception? = null
    fun encodeBody(writeBuffer: WriteBuffer) {}
    fun remainingLength(): Int = 0
}

interface ControlPacketFactory

// ── Packet identifier helpers ────────────────────────────────────────────────

const val NO_PACKET_ID = 0
val validControlPacketIdentifierRange = 1..UShort.MAX_VALUE.toInt()

// ── Marker interfaces matching mqtt models-base shapes ───────────────────────

interface IConnectionAcknowledgment : ControlPacket {
    val isSuccessful: Boolean
    val connectionReason: String
    val sessionPresent: Boolean
    val sessionExpiryInterval: ULong get() = 0uL
    val receiveMaximum: Int get() = UShort.MAX_VALUE.toInt()
    val maximumQos: QualityOfService get() = QualityOfService.EXACTLY_ONCE
    val maxPacketSize: ULong get() = ULong.MAX_VALUE
    val assignedClientIdentifier: String? get() = null
    val serverKeepAlive: Int get() = -1
}

interface IConnectionRequest : ControlPacket {
    val protocolName: String
    val protocolVersion: Int
    val hasUserName: Boolean
    val hasPassword: Boolean
    val cleanStart: Boolean
    val keepAliveTimeoutSeconds: UShort
    val will: WillConfig
    val willFlag: Boolean get() = will is WillConfig.Enabled
    val willRetain: Boolean get() = (will as? WillConfig.Enabled)?.retain ?: false
    val willQos: QualityOfService get() = (will as? WillConfig.Enabled)?.qos ?: QualityOfService.AT_MOST_ONCE
    val willTopic: TopicName? get() = (will as? WillConfig.Enabled)?.topic
    val willPayload: ReadBuffer? get() = (will as? WillConfig.Enabled)?.payload
    val sessionExpiryIntervalSeconds: ULong? get() = null
    val receiveMaximum: UShort get() = UShort.MAX_VALUE
    val maxPacketSize: ULong get() = ULong.MAX_VALUE
    val topicAliasMax: UShort? get() = null
    val clientIdentifier: String
    val userName: String?
    val password: String?
    val willDelayIntervalSeconds: Long get() = 0L
    val payloadFormatIndicator: Boolean get() = false
    val messageExpiryIntervalSeconds: Long? get() = null
    val contentType: String? get() = null
    val responseTopic: TopicName? get() = null
    val correlationData: ReadBuffer? get() = null
    val userProperty: List<Pair<String, String>> get() = emptyList()
}

interface IDisconnectNotification : ControlPacket
interface IPingRequest : ControlPacket
interface IPingResponse : ControlPacket
interface IPublishAcknowledgment : ControlPacket {
    companion object { const val CONTROL_PACKET_VALUE: Byte = 4 }
}
interface IPublishComplete : ControlPacket {
    companion object { const val CONTROL_PACKET_VALUE: Byte = 7 }
}
interface IPublishReceived : ControlPacket {
    companion object { const val CONTROL_PACKET_VALUE: Byte = 5 }
}
interface IPublishRelease : ControlPacket {
    companion object { const val CONTROL_PACKET_VALUE: Byte = 6 }
}
interface ISubscribeAcknowledgement : ControlPacket {
    companion object { const val CONTROL_PACKET_VALUE: Byte = 9 }
}
interface ISubscribeRequest : ControlPacket {
    val subscriptions: Set<ISubscription>
    fun copyWithNewPacketIdentifier(packetIdentifier: Int): ISubscribeRequest
    companion object { const val CONTROL_PACKET_VALUE: Byte = 8 }
}

interface ISubscription {
    val topicFilter: TopicFilter
    val maximumQos: QualityOfService
    val noLocal: Boolean get() = false
    val retainAsPublished: Boolean get() = false
    val retainHandling: RetainHandling get() = RetainHandling.SEND_RETAINED_MESSAGES_AT_TIME_OF_SUBSCRIBE

    enum class RetainHandling(val value: UByte) {
        SEND_RETAINED_MESSAGES_AT_TIME_OF_SUBSCRIBE(0u),
        SEND_RETAINED_MESSAGES_AT_SUBSCRIBE_ONLY_IF_SUBSCRIBE_DOESNT_EXISTS(1u),
        DO_NOT_SEND_RETAINED_MESSAGES(2u),
    }
}

interface IUnsubscribeAcknowledgment : ControlPacket {
    companion object { const val CONTROL_PACKET_VALUE: Byte = 11 }
}
interface IUnsubscribeRequest : ControlPacket {
    fun copyWithNewPacketIdentifier(packetIdentifier: Int): IUnsubscribeRequest
    companion object { const val controlPacketValue: Byte = 10 }
}

// ── PublishMessage (interface) ───────────────────────────────────────────────

interface PublishMessage : ControlPacket {
    val topic: TopicName
    val qualityOfService: QualityOfService
    val dup: Boolean
    val retain: Boolean
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
    fun rawPayload(): ReadBuffer?
    fun expectedResponse(
        reasonCode: ReasonCode = ReasonCode.SUCCESS,
        reasonString: String? = null,
        userProperty: List<Pair<String, String>> = emptyList(),
    ): ControlPacket?
    fun setDupFlagNewPubMessage(): PublishMessage
    fun maybeCopyWithNewPacketIdentifier(packetIdentifier: Int): PublishMessage

    companion object { const val CONTROL_PACKET_VALUE: Byte = 3 }
}
