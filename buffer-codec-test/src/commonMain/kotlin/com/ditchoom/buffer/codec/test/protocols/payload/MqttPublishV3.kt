package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import kotlin.jvm.JvmInline

/**
 * Stage H slice 10a doctrine vector — MQTT v3.1.1 §3.3 PUBLISH
 * concretely typed against `JpegImage` via `@UseCodec`.
 *
 * Wire layout (slice 10a narrow — no `@RemainingLength` field is
 * surfaced in the data class; the buffer's `limit()` is set by the
 * caller before decode, so the trailing `payload` field consumes
 * everything left in the bounded region):
 *
 * ```text
 *   +--------+
 *   | header |   1 byte fixed header (type=3 << 4 | flags)
 *   +--------+--------+--------+
 *   | topic length (UShort BE) | topic UTF-8 body (variable) |
 *   +--------+--------+
 *   | packet identifier (UShort BE) |
 *   +--------+--------+
 *   | payload bytes (variable, decoded by JpegImageCodec)         |
 *   +--------+--------+
 * ```
 *
 * Slice 10a's `MqttPublishV3Concrete` and slice 10b's generic
 * `MqttPublishV3<P : Payload>` (below) coexist — each captures a
 * real consumer pattern. Concrete shape suits protocols where one
 * message always carries a single specific payload type; generic
 * shape suits consumers who pick the payload type at the call site.
 *
 * Slice 10a deliberately narrows the spec shape to validate the
 * typed-payload + `@UseCodec` emit path:
 *   - No QoS-conditional `packetId` (§3.3.2.2): packetId is always
 *     present (will lift to `@When("header.flags.qos > 0")` once
 *     QoS-bit dotted-form predicates exist).
 *   - No `@RemainingLength` var-int as a field (the outer dispatcher
 *     in slice 10d will own the var-int and `setLimit`).
 *   - No `MqttControlPacket`-sealed-parent membership (slice 10d
 *     promotes this into the dispatcher).
 *
 * The `payload` field uses the slice 10a shape: `@RemainingBytes
 * @UseCodec(JpegImageCodec::class) val: JpegImage`. Decode delegates
 * to `JpegImageCodec.decode(buffer, context)` against the buffer's
 * outer-set limit; encode delegates to
 * `JpegImageCodec.encode(buffer, value.payload, context)`.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class MqttPublishV3Concrete(
    val header: MqttFixedHeader,
    @LengthPrefixed val topic: String,
    val packetId: PacketId,
    @RemainingBytes
    @UseCodec(JpegImageCodec::class)
    val payload: JpegImage,
)

/**
 * Stage H slice 10b doctrine vector — MQTT v3.1.1 §3.3 PUBLISH with
 * a generic-bounded payload slot. Same wire layout as
 * `MqttPublishV3Concrete`, but the payload's codec is supplied at
 * the call site instead of being baked in.
 *
 * The generated codec is a class:
 * ```
 * class MqttPublishV3Codec<P : Payload>(private val payloadCodec: Codec<P>)
 *     : Codec<MqttPublishV3<P>>
 * ```
 *
 * Consumers instantiate per payload type:
 * ```
 * val jpegCodec = MqttPublishV3Codec(JpegImageCodec)
 * val textCodec = MqttPublishV3Codec(TextPayloadCodec)
 * ```
 *
 * Wire bytes are identical across instantiations — only the payload
 * decoder differs. The trigger for the generic emit path is the
 * `<P : Payload>` type parameter on the data class plus the
 * `@RemainingBytes val payload: P` field with no `@UseCodec`. The
 * type parameter and the constructor-injected codec coexist with
 * slice 10a's `@UseCodec` path through the `PayloadCodecSource`
 * sealed (`UserCodecObject` for slice 10a, `ConstructorInjected`
 * for slice 10b).
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class MqttPublishV3<P : Payload>(
    val header: MqttFixedHeader,
    @LengthPrefixed val topic: String,
    val packetId: PacketId,
    @RemainingBytes val payload: P,
)

/**
 * Second user-supplied `Payload` for slice 10b — a UTF-8 text
 * payload. Pairing two distinct payload types confirms the generic
 * codec genuinely accepts arbitrary `Codec<P>` rather than
 * accidentally being JpegImage-specific.
 */
data class TextPayload(
    val text: String,
) : Payload

object TextPayloadCodec : Codec<TextPayload> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): TextPayload = TextPayload(buffer.readString(buffer.remaining(), Charset.UTF8))

    override fun encode(
        buffer: WriteBuffer,
        value: TextPayload,
        context: EncodeContext,
    ) {
        buffer.writeString(value.text, Charset.UTF8)
    }

    override fun wireSize(
        value: TextPayload,
        context: EncodeContext,
    ): WireSize = WireSize.BackPatch
}

/**
 * MQTT packet identifier. 16-bit unsigned, modeled as a value class
 * so it can later carry validation (non-zero per §2.3.1) without
 * leaking the raw `UShort` across the surrounding API.
 *
 * Phase J.M.5 audit-2f closed §2.2.1 [MQTT-2.2.1-3] caller-side: a
 * packet identifier of 0 is invalid in both v3 and v5; the init-block
 * `require` makes that impossible to construct.
 */
@JvmInline
@ProtocolMessage
value class PacketId(
    val raw: UShort,
) {
    init {
        require(raw > 0u) {
            "PacketId must be > 0 (spec §2.2.1 [MQTT-2.2.1-3]); got $raw"
        }
    }
}

/**
 * User-supplied `Payload` for the slice 10a vector.
 *
 * Stand-in for an arbitrary user-defined typed payload. The `width`
 * and `height` fields are decoded by `JpegImageCodec`; `data` is the
 * raw image bytes that fill the rest of the bounded region.
 *
 * Implements `com.ditchoom.buffer.codec.Payload` so the §8 raw-bytes
 * walk in `ProtocolMessageProcessor` carves out fields of this type
 * (the marker is the documented escape hatch for "the consumer takes
 * responsibility for the bytes it holds"). User code is free to use
 * `ByteArray` internally — the §8 ban applies only to
 * `@ProtocolMessage` data class fields.
 */
data class JpegImage(
    val width: UShort,
    val height: UShort,
    val data: ByteArray,
) : Payload {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JpegImage) return false
        return width == other.width && height == other.height && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = (width.hashCode() * 31 + height.hashCode()) * 31 + data.contentHashCode()
}

/**
 * Hand-written `Codec<JpegImage>` referenced by
 * `@UseCodec(JpegImageCodec::class)` on the slice 10a vector. The
 * generated `MqttPublishV3Codec` calls
 * `JpegImageCodec.encode(buffer, value.payload, context)` /
 * `JpegImageCodec.decode(buffer, context)` directly — the linker
 * resolution path that slice 10e formalizes for `expect`/`actual`
 * is a no-op for slice 10a's single-platform `object`.
 *
 * Decode reads `width` (UShort BE), `height` (UShort BE), then
 * fills `data` from the remaining bytes in the buffer's bounded
 * region. The bound is set by the caller (`MqttPublishV3Codec`
 * does not set it — slice 10d's outer dispatcher will own the
 * `@RemainingLength` var-int that drives the bound).
 *
 * Encode writes the four header bytes then the data bytes; total
 * wire size is `4 + data.size`.
 */
object JpegImageCodec : Codec<JpegImage> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): JpegImage {
        val width = buffer.readUnsignedShort()
        val height = buffer.readUnsignedShort()
        val data = buffer.readByteArray(buffer.remaining())
        return JpegImage(width, height, data)
    }

    override fun encode(
        buffer: WriteBuffer,
        value: JpegImage,
        context: EncodeContext,
    ) {
        buffer.writeUShort(value.width)
        buffer.writeUShort(value.height)
        buffer.writeBytes(value.data)
    }

    override fun wireSize(
        value: JpegImage,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(4 + value.data.size)
}
