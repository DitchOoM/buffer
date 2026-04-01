package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import kotlin.jvm.JvmInline

/**
 * WebSocket frame dispatch by opcode (RFC 6455 §5.2).
 *
 * Wire format (first byte):
 * ```
 * bit 7:    FIN
 * bits 6-4: RSV1, RSV2, RSV3
 * bits 3-0: opcode (0x0-0xF)
 * ```
 *
 * Bottom-nibble @DispatchOn — extracts opcode from bits 3-0.
 * This is the mirror of MQTT's top-nibble extraction (shr 4).
 *
 * Limitations: models unmasked frames with 7-bit payload length only
 * (payloads ≤ 125 bytes). Extended 16-bit and 64-bit lengths and masking
 * are handled by the existing [WsMaskedFrame] / [WsUnmaskedFrame] models.
 */

/** WebSocket first byte: FIN/RSV flags + opcode. */
@JvmInline
@ProtocolMessage
value class WsOpcodeByte(val raw: UByte) {
    @DispatchValue
    val opcode: Int get() = raw.toInt() and 0x0F

    val fin: Boolean get() = (raw.toInt() shr 7) and 1 == 1
    val rsv1: Boolean get() = (raw.toInt() shr 6) and 1 == 1
    val rsv2: Boolean get() = (raw.toInt() shr 5) and 1 == 1
    val rsv3: Boolean get() = (raw.toInt() shr 4) and 1 == 1
}

/**
 * WebSocket frames dispatched by opcode.
 * Models the non-payload-bearing control frames that have fixed structure.
 *
 * wire values use FIN=1 (0x80) OR'd with opcode, since control frames
 * MUST have FIN set (RFC 6455 §5.5).
 */
@DispatchOn(WsOpcodeByte::class)
@ProtocolMessage
sealed interface WsControlFrame {
    /** Opcode 0x8: Close frame — optional status code + reason. */
    @PacketType(value = 0x08, wire = 0x88) // FIN=1, opcode=8
    @ProtocolMessage
    data class Close(
        val payloadLength: UByte, // 7-bit length in byte 2 (mask=0 for server-to-client)
        val statusCode: UShort,
        @RemainingBytes val reason: String,
    ) : WsControlFrame

    /** Opcode 0x9: Ping frame — optional application data. */
    @PacketType(value = 0x09, wire = 0x89) // FIN=1, opcode=9
    @ProtocolMessage
    data class Ping(
        val payloadLength: UByte,
        @RemainingBytes val data: String,
    ) : WsControlFrame

    /** Opcode 0xA: Pong frame — echoes ping data. */
    @PacketType(value = 0x0A, wire = 0x8A) // FIN=1, opcode=A
    @ProtocolMessage
    data class Pong(
        val payloadLength: UByte,
        @RemainingBytes val data: String,
    ) : WsControlFrame
}
