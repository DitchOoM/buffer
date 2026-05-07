package com.ditchoom.buffer.codec.test.protocols.websocket

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.annotations.When
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryData
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec
import kotlin.jvm.JvmInline

/**
 * RFC 6455 WebSocket frame fixture for the buffer-codec processor.
 *
 * Mirrors the production model in the `websocket` repo (`com.ditchoom.websocket.frame.WsCodec`)
 * — adapted to the dispatch shape this branch supports — so a regression in any of the
 * annotations the websocket repo relies on shows up here as a focused failure.
 *
 * ### Wire layout (RFC 6455 §5.2)
 *
 * ```text
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-------+-+-------------+-------------------------------+
 * |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
 * |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
 * |N|V|V|V|       |S|             |   (if payload len==126/127)   |
 * | |1|2|3|       |K|             |                               |
 * +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
 * |     Extended payload length continued, if payload len == 127  |
 * + - - - - - - - - - - - - - - - +-------------------------------+
 * |                               |Masking-key, if MASK set to 1  |
 * +-------------------------------+-------------------------------+
 * | Masking-key (continued)       |          Payload Data         |
 * +-------------------------------+ - - - - - - - - - - - - - - - +
 * ```
 *
 * ### Dispatch shape on this branch
 *
 * `@DispatchOn` on the current branch requires a `@JvmInline value class` discriminator
 * (slice 6). Production code in the websocket repo uses the multi-field data-class form
 * `@DispatchOn(WsFrameHeader::class, framing = WsFraming::class)` — that variant ships
 * in `buffer-codec 4.3.0-SNAPSHOT`, which the websocket repo depends on but
 * `feature/directional-codec` (this branch) is upstream of.
 *
 * To get genuine sealed dispatch on this branch the discriminator is [FrameHeaderByte1]
 * (single-byte value class). Each variant inlines the rest of the header structure
 * (byte2, optional extended length, optional masking key) rather than nesting a shared
 * [WsFrameHeader] field. The duplication is intentional: it lets the dispatcher land on
 * the value-class shape it supports today, and pins down the exact `@When`-grammar-1
 * predicates (`byte2.extended16` / `byte2.masked`) inside every variant — which is what
 * the websocket repo's per-variant codecs each contain anyway.
 *
 * [WsFrameHeader] is kept as a separate `@ProtocolMessage` for header-shape isolation
 * tests; it does not participate in the dispatch.
 *
 * ### What this fixture pins down
 *
 * - **Sealed dispatch via `@DispatchOn(FrameHeaderByte1::class)` + `@PacketType` per
 *   opcode.** Reserved opcodes 0x3-0x7 and 0xB-0xF have no `@PacketType` and the
 *   dispatcher rejects them at decode — RFC 6455 §5.2 protocol-error semantics for free.
 * - **Bit-packed value class as discriminator.** [FrameHeaderByte1.opcode] is the
 *   `@DispatchValue`; the high nibble (FIN/RSV) round-trips through the raw byte without
 *   the dispatcher caring about it.
 * - **`@When("byte2.extended16/64")` grammar 1** repeated across every variant — value-
 *   class property of a sibling field gates an optional extended-length field.
 * - **`@When("byte2.masked")` grammar 1** — same shape gates the optional 4-byte mask.
 * - **`@When("remaining >= 2")` grammar 2** on the close variant — close body absent for
 *   empty payload, present when ≥2 bytes follow.
 * - **`@RemainingBytes` with mixed slot types** — `String` (text frame, UTF-8 validity
 *   gate is built into `String` decode) and `BinaryData` via `@UseCodec(BinaryDataCodec)`
 *   (zero-boxing opaque payload — the same shape MQTT v5 `CorrelationData` /
 *   `AuthenticationData` use).
 *
 * ### What this fixture deliberately does NOT cover
 *
 * - **Variable-frame-size peek.** RFC 6455 frames carry payload length inside the header
 *   (byte 2 + 16/64-bit extension), not as a body-length prefix. Generic `@DispatchOn`
 *   framing cannot infer that without a custom peek; the websocket repo does it via the
 *   `framing = WsFraming::class` parameter (a future buffer-codec feature). Tests
 *   pre-bound the buffer to known frame sizes before calling the codec, mirroring how
 *   `WebSocketCodec.readNextFrame` reads `stream.readBuffer(totalFrameSize)` after a
 *   handwritten peek.
 * - **Masking-XOR application.** A per-byte transform that runs in the connection layer
 *   (`buffer.xorMask(key)`); the codec round-trips the masking key and raw masked bytes,
 *   the unmask happens off-codec. Demonstrated explicitly in the masked-pong test.
 * - **Stateful policy** (fragmentation rules, control-frame ≤125, close-handshake
 *   choreography, ping echo). These cross frame boundaries; codecs are pure per-frame.
 */

// ──────────────────────── Header bit-packing ────────────────────────

/**
 * Frame header byte 1 — FIN/RSV1/RSV2/RSV3/opcode packed into a single `UByte`.
 *
 * Doubles as the `@DispatchOn` discriminator for [WsFrame]: [opcode] is the
 * `@DispatchValue` the sealed dispatcher reads to pick the variant.
 */
@JvmInline
@ProtocolMessage
value class FrameHeaderByte1(
    val raw: UByte,
) {
    inline val fin: Boolean get() = (raw.toInt() and 0x80) != 0
    inline val rsv1: Boolean get() = (raw.toInt() and 0x40) != 0
    inline val rsv2: Boolean get() = (raw.toInt() and 0x20) != 0
    inline val rsv3: Boolean get() = (raw.toInt() and 0x10) != 0

    @DispatchValue
    val opcode: Int get() = raw.toInt() and 0x0F

    companion object {
        fun pack(
            fin: Boolean,
            rsv1: Boolean,
            rsv2: Boolean,
            rsv3: Boolean,
            opcode: Int,
        ): FrameHeaderByte1 {
            var b = opcode and 0x0F
            if (fin) b = b or 0x80
            if (rsv1) b = b or 0x40
            if (rsv2) b = b or 0x20
            if (rsv3) b = b or 0x10
            return FrameHeaderByte1(b.toUByte())
        }
    }
}

/**
 * Frame header byte 2 — MASK bit + 7-bit length indicator packed into a single `UByte`.
 *
 * The boolean properties [extended16], [extended64], and [masked] feed `@When` grammar 1
 * predicates on each variant's optional extended-length and masking-key fields.
 */
@JvmInline
@ProtocolMessage
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
                    payloadSize <= 125L -> payloadSize.toInt()
                    payloadSize <= 0xFFFFL -> 126
                    else -> 127
                }
            return WsHeaderByte2((maskBit or len7).toUByte())
        }
    }
}

/** 4-byte masking key. Stored as `UInt` so the codec emits/consumes 4 contiguous BE bytes. */
@JvmInline
@ProtocolMessage
value class WsMaskingKey(
    val raw: UInt,
)

// ──────────────────────── Standalone header (for header-only round-trip tests) ────────────────────────

/**
 * WebSocket frame header — standalone `@ProtocolMessage` for header-shape isolation
 * tests. Does NOT participate in the [WsFrame] dispatch (the dispatcher reads byte1 only
 * and routes to a variant that re-reads the full header structure inline).
 *
 * This duplicates the variant header layout, which is the cost of the value-class
 * discriminator constraint on this branch. When `framing = WsFraming::class` lands on
 * `@DispatchOn`, the dispatcher can route on this multi-field shape directly and the
 * variants can collapse to `header: WsFrameHeader` + payload.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class WsFrameHeader(
    val byte1: FrameHeaderByte1,
    val byte2: WsHeaderByte2,
    @When("byte2.extended16") val extendedLength16: UShort? = null,
    @When("byte2.extended64") val extendedLength64: Long? = null,
    @When("byte2.masked") val maskingKey: WsMaskingKey? = null,
) {
    val payloadLength: Long
        get() = extendedLength64 ?: extendedLength16?.toLong() ?: byte2.lengthIndicator.toLong()

    val wireSize: Int
        get() =
            2 +
                (if (extendedLength16 != null) 2 else 0) +
                (if (extendedLength64 != null) 8 else 0) +
                (if (maskingKey != null) 4 else 0)

    val opcodeValue: Int get() = byte1.opcode

    companion object {
        fun build(
            byte1: FrameHeaderByte1,
            payloadSize: Long,
            maskingKey: WsMaskingKey? = null,
        ): WsFrameHeader {
            val byte2 = WsHeaderByte2.pack(payloadSize, masked = maskingKey != null)
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

// ──────────────────────── Close-frame body ────────────────────────

/**
 * Close-frame body per RFC 6455 §5.5.1 / §7.4.1: 2-byte BE status code followed by an
 * optional UTF-8 reason that fills the remainder of the buffer. The parent `Close`
 * variant must have already bounded the buffer to the close payload extent — [reason]
 * absorbs everything after the status code.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class WsCloseBody(
    val statusCode: UShort,
    @RemainingBytes val reason: String,
)

// ──────────────────────── Sealed dispatch ────────────────────────

/**
 * WebSocket frame, sealed dispatched on opcode (low nibble of [FrameHeaderByte1.raw]).
 *
 * Reserved opcodes 0x3-0x7 (non-control) and 0xB-0xF (control) intentionally have no
 * `@PacketType` — the dispatcher rejects them at decode, mirroring RFC 6455 §5.2's
 * protocol-error rule.
 *
 * Each variant inlines the full header structure (byte1 + byte2 + optional ext lengths
 * + optional masking key) before its payload. Nesting `header: WsFrameHeader` instead
 * would require the data-class-discriminator form of `@DispatchOn` from a future
 * buffer-codec snapshot.
 */
@DispatchOn(FrameHeaderByte1::class)
@ProtocolMessage(wireOrder = Endianness.Big)
sealed interface WsFrame {
    /** Text frame (opcode 0x1). RFC 6455 §5.6 — payload MUST be valid UTF-8 (the `String` decoder gates this). */
    @PacketType(0x1)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Text(
        val byte1: FrameHeaderByte1,
        val byte2: WsHeaderByte2,
        @When("byte2.extended16") val extendedLength16: UShort? = null,
        @When("byte2.extended64") val extendedLength64: Long? = null,
        @When("byte2.masked") val maskingKey: WsMaskingKey? = null,
        @RemainingBytes val payload: String,
    ) : WsFrame

    /** Binary frame (opcode 0x2). RFC 6455 §5.6 — opaque application bytes. */
    @PacketType(0x2)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Binary(
        val byte1: FrameHeaderByte1,
        val byte2: WsHeaderByte2,
        @When("byte2.extended16") val extendedLength16: UShort? = null,
        @When("byte2.extended64") val extendedLength64: Long? = null,
        @When("byte2.masked") val maskingKey: WsMaskingKey? = null,
        @RemainingBytes @UseCodec(BinaryDataCodec::class) val payload: BinaryData,
    ) : WsFrame

    /** Continuation frame (opcode 0x0). RFC 6455 §5.4 fragment payload. */
    @PacketType(0x0)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Continuation(
        val byte1: FrameHeaderByte1,
        val byte2: WsHeaderByte2,
        @When("byte2.extended16") val extendedLength16: UShort? = null,
        @When("byte2.extended64") val extendedLength64: Long? = null,
        @When("byte2.masked") val maskingKey: WsMaskingKey? = null,
        @RemainingBytes @UseCodec(BinaryDataCodec::class) val payload: BinaryData,
    ) : WsFrame

    /** Close frame (opcode 0x8). Body absent for empty payload, present when ≥2 bytes follow. */
    @PacketType(0x8)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Close(
        val byte1: FrameHeaderByte1,
        val byte2: WsHeaderByte2,
        @When("byte2.extended16") val extendedLength16: UShort? = null,
        @When("byte2.extended64") val extendedLength64: Long? = null,
        @When("byte2.masked") val maskingKey: WsMaskingKey? = null,
        @When("remaining >= 2") val body: WsCloseBody? = null,
    ) : WsFrame

    /** Ping frame (opcode 0x9). RFC 6455 §5.5.2 — payload is application data. */
    @PacketType(0x9)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Ping(
        val byte1: FrameHeaderByte1,
        val byte2: WsHeaderByte2,
        @When("byte2.extended16") val extendedLength16: UShort? = null,
        @When("byte2.extended64") val extendedLength64: Long? = null,
        @When("byte2.masked") val maskingKey: WsMaskingKey? = null,
        @RemainingBytes @UseCodec(BinaryDataCodec::class) val payload: BinaryData,
    ) : WsFrame

    /** Pong frame (opcode 0xA). RFC 6455 §5.5.3 — must echo the corresponding Ping's payload. */
    @PacketType(0xA)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Pong(
        val byte1: FrameHeaderByte1,
        val byte2: WsHeaderByte2,
        @When("byte2.extended16") val extendedLength16: UShort? = null,
        @When("byte2.extended64") val extendedLength64: Long? = null,
        @When("byte2.masked") val maskingKey: WsMaskingKey? = null,
        @RemainingBytes @UseCodec(BinaryDataCodec::class) val payload: BinaryData,
    ) : WsFrame
}
