// PHASE 9 FIXTURE — copied from websocket/src/commonMain/kotlin/com/ditchoom/websocket/frame/WsCodec.kt
// Deleted in Phase 9 Step 7 once consumer cutover is verified.
package com.ditchoom.buffer.codec.test.consumer.websocket.frame

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.When
import com.ditchoom.buffer.codec.test.consumer.websocket.Opcode
import kotlin.jvm.JvmInline

@JvmInline
value class FrameHeaderByte1(
    val raw: UByte,
) {
    inline val fin: Boolean get() = (raw.toInt() and 0x80) != 0
    inline val rsv1: Boolean get() = (raw.toInt() and 0x40) != 0
    inline val rsv2: Boolean get() = (raw.toInt() and 0x20) != 0
    inline val rsv3: Boolean get() = (raw.toInt() and 0x10) != 0
    inline val opcode: Opcode get() = Opcode.fromInt(raw.toInt() and 0x0F)

    companion object {
        fun pack(
            fin: Boolean,
            rsv1: Boolean,
            rsv2: Boolean,
            rsv3: Boolean,
            opcode: Opcode,
        ): FrameHeaderByte1 {
            var byte1 = opcode.value.toInt() and 0x0F
            if (fin) byte1 = byte1 or 0x80
            if (rsv1) byte1 = byte1 or 0x40
            if (rsv2) byte1 = byte1 or 0x20
            if (rsv3) byte1 = byte1 or 0x10
            return FrameHeaderByte1(byte1.toUByte())
        }
    }
}

@JvmInline
value class WsHeaderByte2(
    val raw: UByte,
) {
    inline val masked: Boolean get() = (raw.toInt() and 0x80) != 0
    inline val lengthIndicator: Int get() = raw.toInt() and 0x7F
    inline val extended16: Boolean get() = lengthIndicator == 126
    inline val extended64: Boolean get() = lengthIndicator == 127

    companion object {
        fun pack(
            payloadSize: Long,
            masked: Boolean,
        ): WsHeaderByte2 {
            val maskBit = if (masked) 0x80 else 0
            val len7 =
                when {
                    payloadSize <= 125 -> payloadSize.toInt()
                    payloadSize <= 65535 -> 126
                    else -> 127
                }
            return WsHeaderByte2((maskBit or len7).toUByte())
        }
    }
}

@ProtocolMessage
data class WsFrameHeader(
    val byte1: FrameHeaderByte1,
    val byte2: WsHeaderByte2,
    @When("byte2.extended16") val extendedLength16: UShort? = null,
    @When("byte2.extended64") val extendedLength64: Long? = null,
    @When("byte2.masked") val maskingKey: WsMaskingKey? = null,
) {
    val payloadLength: Long
        get() = extendedLength64 ?: extendedLength16?.toLong() ?: byte2.lengthIndicator.toLong()

    val masked: Boolean get() = byte2.masked

    val wireSize: Int
        get() =
            2 + (if (extendedLength16 != null) 2 else 0) +
                (if (extendedLength64 != null) 8 else 0) +
                (if (maskingKey != null) 4 else 0)

    @DispatchValue
    val opcodeValue: Int get() = byte1.raw.toInt() and 0x0F

    companion object {
        fun build(
            byte1: FrameHeaderByte1,
            payloadSize: Long,
            masked: Boolean,
            maskingKey: WsMaskingKey? = null,
        ): WsFrameHeader {
            val byte2 = WsHeaderByte2.pack(payloadSize, masked)
            return WsFrameHeader(
                byte1 = byte1,
                byte2 = byte2,
                extendedLength16 = if (byte2.extended16) payloadSize.toUShort() else null,
                extendedLength64 = if (byte2.extended64) payloadSize else null,
                maskingKey = maskingKey,
            )
        }
    }
}

@JvmInline
value class WsMaskingKey(
    val raw: UInt,
)

@ProtocolMessage
data class WsCloseBody(
    val statusCode: CloseCode,
    @RemainingBytes val reason: String,
)

// ──────────────────────── WsFrame sealed dispatch ────────────────────────

@DispatchOn(WsFrameHeader::class, framing = WsFraming::class)
@ProtocolMessage
sealed interface WsFrame {
    val header: WsFrameHeader

    @PacketType(0x1)
    @ProtocolMessage
    data class Text<@Payload T>(
        override val header: WsFrameHeader,
        @RemainingBytes val payload: T,
    ) : WsFrame

    @PacketType(0x2)
    @ProtocolMessage
    data class Binary<@Payload T>(
        override val header: WsFrameHeader,
        @RemainingBytes val payload: T,
    ) : WsFrame

    @PacketType(0x0)
    @ProtocolMessage
    data class Continuation<@Payload T>(
        override val header: WsFrameHeader,
        @RemainingBytes val payload: T,
    ) : WsFrame

    @PacketType(0x8)
    @ProtocolMessage
    data class Close(
        override val header: WsFrameHeader,
        @When("remaining >= 2") val body: WsCloseBody? = null,
    ) : WsFrame

    @PacketType(0x9)
    @ProtocolMessage
    data class Ping<@Payload T>(
        override val header: WsFrameHeader,
        @RemainingBytes val payload: T,
    ) : WsFrame

    @PacketType(0xA)
    @ProtocolMessage
    data class Pong<@Payload T>(
        override val header: WsFrameHeader,
        @RemainingBytes val payload: T,
    ) : WsFrame
}

class FrameParseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
