package com.ditchoom.buffer.codec.test.protocols.websocket

import com.ditchoom.buffer.codec.FrameDetector
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.When
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.jvm.JvmInline

/*
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
 * ### Dispatch shape
 *
 * `@DispatchOn` requires a `@JvmInline value class` discriminator. The discriminator is
 * [FrameHeaderByte1] (single-byte value class); [FrameHeaderByte1.opcode] is the
 * `@DispatchValue`. Each variant inlines the rest of the header structure (byte2,
 * optional extended length, optional masking key) rather than nesting a shared
 * [WsFrameHeader] field — the dispatcher self-frames the discriminator byte, then each
 * variant's codec re-reads byte 1 alongside the trailing header fields. The duplication
 * is intentional: it pins down the exact `@When`-grammar-1 predicates
 * (`byte2.extended16` / `byte2.masked`) inside every variant.
 *
 * [WsFrameHeader] survives as a separate `@ProtocolMessage` for header-shape isolation
 * tests; it does not participate in the dispatch.
 *
 * ### Payload shape — `<out P : Payload>` on the parent, `<P : Payload>` on data variants
 *
 * `@ProtocolMessage` model fields must not be `ByteArray`/`ReadBuffer`/`PlatformBuffer` —
 * a buffer field forces a copy at the codec boundary, taking the choice away from the
 * consumer. The framework's design principle (per `Codec.kt:108-116`) is that
 * **zero-copy is the easy path; copies are explicit and the consumer decides when to
 * make them**. So data-bearing variants ([Text], [Binary], [Continuation], [Ping],
 * [Pong]) carry `<P : Payload>` + `@RemainingBytes val payload: P`; the consumer
 * supplies a `Codec<P>` to the constructor-injected `WsFrameCodec<P>` and the framework
 * decodes payload bytes directly into the consumer's typed model. [Close] has no
 * generic payload (only [WsCloseBody]), so it extends `WsFrame<Nothing>`.
 *
 * Same shape `MqttPacket<out P : Payload>` uses for `Publish<P : Payload>` (mqtt
 * fixture, `MqttPacket.kt:115-264`) and `Slice14cGenericFramedDispatch<out P : Payload>`
 * uses for `WithPayload<P : Payload>`. The generated codec is a class:
 *
 * ```kotlin
 * class WsFrameCodec<P : Payload>(private val payloadCodec: Codec<P>) : Codec<WsFrame<P>>
 * ```
 *
 * Consumers instantiate per payload type: `WsFrameCodec(TextPayloadCodec)` to decode
 * text frames into `TextPayload`; `WsFrameCodec(BinaryDataCodec)` to decode binary
 * frames into `BinaryData`. Wire bytes are identical across instantiations.
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
 * - **`<out P : Payload>` sealed parent with mixed payload-bearing and `<Nothing>`
 *   variants** — same shape MQTT v3.1.1 PUBLISH + the no-payload control packets use.
 *
 * ### What this fixture deliberately does NOT cover
 *
 * - **Variable-frame-size peek.** RFC 6455 frames carry payload length inside the header
 *   (byte 2 + 16/64-bit extension), not as a body-length prefix. Generic `@DispatchOn`
 *   framing cannot infer that without a custom peek; the websocket repo does it in
 *   `WebSocketCodec.readNextFrame` via a hand-written peek + `stream.readBuffer(size)`.
 *   Tests here pre-bound the buffer to known frame sizes before calling the codec.
 * - **Masking-XOR application.** A per-byte transform that runs in the connection layer
 *   (`buffer.xorMask(key)`); the codec round-trips the masking key and raw masked bytes,
 *   the unmask happens off-codec. Demonstrated explicitly in the masked-pong test.
 * - **Stateful policy** (fragmentation rules, control-frame ≤125, close-handshake
 *   choreography, ping echo, fragmented-Text UTF-8 reassembly). These cross frame
 *   boundaries; codecs are pure per-frame. Fragmented-Text reassembly across multiple
 *   `Continuation<P>` frames lives in the websocket repo's `MessageAssembler`.
 */

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
 * + optional masking key) before its payload. The data-bearing variants ([Text],
 * [Binary], [Continuation], [Ping], [Pong]) carry a generic `<P : Payload>` slot
 * decoded by a consumer-supplied `Codec<P>`; [Close] carries the structured
 * [WsCloseBody] and extends `WsFrame<Nothing>`.
 */
@DispatchOn(FrameHeaderByte1::class)
@ProtocolMessage(wireOrder = Endianness.Big)
sealed interface WsFrame<out P : Payload> {
    /**
     * Text frame (opcode 0x1). RFC 6455 §5.6 — payload bytes are UTF-8 application data.
     * Per-frame UTF-8 validation is the consumer-codec's responsibility (a
     * `Codec<TextPayload>` that calls `buffer.readString(remaining, Charset.UTF8)`
     * throws on malformed input); fragmented-text reassembly across [Continuation]
     * frames lives in the consumer's assembler, not at the codec layer.
     */
    @PacketType(0x1)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Text<P : Payload>(
        val byte1: FrameHeaderByte1,
        val byte2: WsHeaderByte2,
        @When("byte2.extended16") val extendedLength16: UShort? = null,
        @When("byte2.extended64") val extendedLength64: Long? = null,
        @When("byte2.masked") val maskingKey: WsMaskingKey? = null,
        @RemainingBytes val payload: P,
    ) : WsFrame<P>

    /** Binary frame (opcode 0x2). RFC 6455 §5.6 — opaque application bytes. */
    @PacketType(0x2)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Binary<P : Payload>(
        val byte1: FrameHeaderByte1,
        val byte2: WsHeaderByte2,
        @When("byte2.extended16") val extendedLength16: UShort? = null,
        @When("byte2.extended64") val extendedLength64: Long? = null,
        @When("byte2.masked") val maskingKey: WsMaskingKey? = null,
        @RemainingBytes val payload: P,
    ) : WsFrame<P>

    /** Continuation frame (opcode 0x0). RFC 6455 §5.4 fragment payload. */
    @PacketType(0x0)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Continuation<P : Payload>(
        val byte1: FrameHeaderByte1,
        val byte2: WsHeaderByte2,
        @When("byte2.extended16") val extendedLength16: UShort? = null,
        @When("byte2.extended64") val extendedLength64: Long? = null,
        @When("byte2.masked") val maskingKey: WsMaskingKey? = null,
        @RemainingBytes val payload: P,
    ) : WsFrame<P>

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
    ) : WsFrame<Nothing>

    /** Ping frame (opcode 0x9). RFC 6455 §5.5.2 — payload is application data. */
    @PacketType(0x9)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Ping<P : Payload>(
        val byte1: FrameHeaderByte1,
        val byte2: WsHeaderByte2,
        @When("byte2.extended16") val extendedLength16: UShort? = null,
        @When("byte2.extended64") val extendedLength64: Long? = null,
        @When("byte2.masked") val maskingKey: WsMaskingKey? = null,
        @RemainingBytes val payload: P,
    ) : WsFrame<P>

    /** Pong frame (opcode 0xA). RFC 6455 §5.5.3 — must echo the corresponding Ping's payload. */
    @PacketType(0xA)
    @ProtocolMessage(wireOrder = Endianness.Big)
    data class Pong<P : Payload>(
        val byte1: FrameHeaderByte1,
        val byte2: WsHeaderByte2,
        @When("byte2.extended16") val extendedLength16: UShort? = null,
        @When("byte2.extended64") val extendedLength64: Long? = null,
        @When("byte2.masked") val maskingKey: WsMaskingKey? = null,
        @RemainingBytes val payload: P,
    ) : WsFrame<P>

    /**
     * Consumer-supplied frame sizing — the piece RFC 6455 framing needs that the
     * generated peek walker cannot derive. The payload length is **escape-coded**
     * across header fields (`byte2.lengthIndicator`, escaping to a 16- or 64-bit
     * extended length at 126/127), and a masking key folds **between** the length
     * and the payload — neither a `@LengthPrefixed`/`@LengthFrom` nor a bounding
     * codec can express that, so `WsFrameCodec.peekFrameSize` would otherwise fall
     * back to `NoFraming` (and the websocket repo hand-writes this in
     * `WebSocketCodec.readNextFrame`).
     *
     * Because this companion implements [FrameDetector], the processor makes the
     * generated `WsFrameCodec.peekFrameSize` delegate here instead. The companion
     * owns ONLY framing (the total byte count); field decode/encode stay
     * generated. The load-bearing contract — `peek.bytes == decode consumption`
     * for any fully-buffered frame, `NeedsMoreData` for every shorter prefix — is
     * locked by `WsFramePeekCodecTest`'s drip-feed across all three length classes
     * (7-bit / 16-bit / 64-bit), masked and unmasked.
     *
     * Wire walked (RFC 6455 §5.2): `byte1 | byte2 | [ext-len 16 if ind==126 |
     * ext-len 64 if ind==127] | [mask 4 if MASK] | payload[len]`. Extended lengths
     * are network (big-endian) order; the 64-bit length's MSB is 0 by spec, so it
     * fits a non-negative `Long`.
     */
    companion object : FrameDetector {
        override fun peekFrameSize(
            stream: StreamProcessor,
            baseOffset: Int,
        ): PeekResult {
            // byte1 + byte2 are needed before the length shape is known.
            if (stream.available() - baseOffset < 2) return PeekResult.NeedsMoreData
            val byte2 = stream.peekByte(baseOffset + 1).toInt() and 0xFF
            val masked = (byte2 and 0x80) != 0
            val indicator = byte2 and 0x7F
            var offset = 2
            val payloadLength: Long =
                when (indicator) {
                    126 -> {
                        if (stream.available() - baseOffset < offset + 2) return PeekResult.NeedsMoreData
                        val hi = stream.peekByte(baseOffset + offset).toInt() and 0xFF
                        val lo = stream.peekByte(baseOffset + offset + 1).toInt() and 0xFF
                        offset += 2
                        ((hi shl 8) or lo).toLong()
                    }
                    127 -> {
                        if (stream.available() - baseOffset < offset + 8) return PeekResult.NeedsMoreData
                        var v = 0L
                        for (i in 0 until 8) {
                            v = (v shl 8) or (stream.peekByte(baseOffset + offset + i).toLong() and 0xFF)
                        }
                        offset += 8
                        v
                    }
                    else -> indicator.toLong()
                }
            if (masked) offset += 4
            val total = offset + payloadLength.toInt()
            return if (stream.available() - baseOffset >= total) {
                PeekResult.Complete(total)
            } else {
                PeekResult.NeedsMoreData
            }
        }
    }
}
