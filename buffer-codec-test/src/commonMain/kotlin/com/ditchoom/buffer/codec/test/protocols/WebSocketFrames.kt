package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.WhenTrue
import kotlin.jvm.JvmInline

@JvmInline
value class WsHeaderByte1(
    val raw: UByte,
) {
    val fin: Boolean get() = (raw.toInt() shr 7) and 1 == 1
    val rsv1: Boolean get() = (raw.toInt() shr 6) and 1 == 1
    val rsv2: Boolean get() = (raw.toInt() shr 5) and 1 == 1
    val rsv3: Boolean get() = (raw.toInt() shr 4) and 1 == 1
    val opcode: Int get() = raw.toInt() and 0xF
}

@JvmInline
value class WsHeaderByte2(
    val raw: UByte,
) {
    val mask: Boolean get() = (raw.toInt() shr 7) and 1 == 1

    /** Raw 7-bit payload length field from the wire. Values 126/127 are indicators for extended length. */
    val payloadLengthCode: Int get() = raw.toInt() and 0x7F

    /** True when the next 2 bytes contain the actual payload length (16-bit, for lengths 126..65535). */
    val hasExtendedLength16: Boolean get() = payloadLengthCode == 126

    /** True when the next 8 bytes contain the actual payload length (64-bit, for lengths > 65535). */
    val hasExtendedLength64: Boolean get() = payloadLengthCode == 127

    /**
     * Returns the payload length if it fits in the 7-bit field (0..125),
     * or null if an extended length follows on the wire.
     */
    val smallPayloadLength: Int? get() = payloadLengthCode.takeIf { it <= 125 }
}

@JvmInline
value class WsCloseCode(
    val raw: UShort,
) {
    companion object {
        val NORMAL = WsCloseCode(1000u)
        val GOING_AWAY = WsCloseCode(1001u)
        val PROTOCOL_ERROR = WsCloseCode(1002u)
        val UNSUPPORTED_DATA = WsCloseCode(1003u)
        val NO_STATUS = WsCloseCode(1005u)
        val ABNORMAL_CLOSURE = WsCloseCode(1006u)
        val INVALID_PAYLOAD = WsCloseCode(1007u)
        val POLICY_VIOLATION = WsCloseCode(1008u)
        val MESSAGE_TOO_BIG = WsCloseCode(1009u)
        val MANDATORY_EXTENSION = WsCloseCode(1010u)
        val INTERNAL_ERROR = WsCloseCode(1011u)
    }
}

@JvmInline
value class WsMaskingKey(
    val raw: UInt,
)

@ProtocolMessage
data class WsFrameHeader(
    val byte1: WsHeaderByte1,
    val byte2: WsHeaderByte2,
)

@ProtocolMessage
data class WsUnmaskedFrame(
    val byte1: WsHeaderByte1,
    val byte2: WsHeaderByte2,
    @RemainingBytes val payload: String,
)

@ProtocolMessage
data class WsMaskedFrame(
    val byte1: WsHeaderByte1,
    val byte2: WsHeaderByte2,
    @WhenTrue("byte2.mask") val maskingKey: WsMaskingKey? = null,
    @RemainingBytes val payload: String,
)

@ProtocolMessage
data class WsCloseBody(
    val statusCode: WsCloseCode,
    @RemainingBytes val reason: String,
)
