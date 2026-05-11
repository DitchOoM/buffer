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
 * Doctrine vector — MQTT v3.1.1 §3.3 PUBLISH
 * concretely typed against `JpegImage` via `@UseCodec`.
 *
 * Wire layout ( narrow — no `@RemainingLength` field is
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
 * The concrete `MqttPublishV3Concrete` and the generic
 * `MqttPublishV3<P : Payload>` (below) coexist — each captures a
 * real consumer pattern. Concrete shape suits protocols where one
 * message always carries a single specific payload type; generic
 * shape suits consumers who pick the payload type at the call site.
 *
 * Deliberately narrows the spec shape to validate the
 * typed-payload + `@UseCodec` emit path:
 *   - No QoS-conditional `packetId` (§3.3.2.2): packetId is always
 *     present (will lift to `@When("header.flags.qos > 0")` once
 *     QoS-bit dotted-form predicates exist).
 *   - No `@RemainingLength` var-int as a field (the outer dispatcher
 *     owns the var-int and `setLimit`).
 *   - No `MqttControlPacket`-sealed-parent membership (the dispatcher
 *     would promote this fixture into the larger sealed hierarchy).
 *
 * The `payload` field uses the shape: `@RemainingBytes
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
 * Doctrine vector — MQTT v3.1.1 §3.3 PUBLISH with
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
 * the `@UseCodec` path through the `PayloadCodecSource` sealed
 * (`UserCodecObject` for the codec-on-the-field shape,
 * `ConstructorInjected` for the generic-payload shape).
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class MqttPublishV3<P : Payload>(
    val header: MqttFixedHeader,
    @LengthPrefixed val topic: String,
    val packetId: PacketId,
    @RemainingBytes val payload: P,
)

/**
 * Second user-supplied `Payload` for — a UTF-8 text
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
 * Closed §2.2.1 [MQTT-2.2.1-3] caller-side: a
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
 * User-supplied `Payload` for the vector. Reshaped under buffer-codec
 * lockdown v1 (Change 1) to demonstrate the canonical "decode straight
 * into a platform-native handle" pattern.
 *
 * The wire shape (width UShort + height UShort + pixel bytes) is unchanged,
 * but the storage is now an opaque [PlatformBitmap] handle. The KSP walker
 * sees the property `nativeBitmap: PlatformBitmap`, finds it is not a
 * forbidden type / Payload / value class — and stops. Whatever the platform
 * actual stores internally (a `BufferedImage`, `android.graphics.Bitmap`,
 * `UIImage`, `web.images.ImageBitmap`) is invisible to the rule.
 *
 * The companion `invoke` shim and computed `width` / `height` / `data`
 * properties preserve the call shape `JpegImage(widthUShort, heightUShort,
 * byteArrayOf(...))` and accessors `decoded.width` / `decoded.data` used
 * across the existing test suite.
 */
class JpegImage internal constructor(
    val nativeBitmap: PlatformBitmap,
) : Payload {
    val width: UShort get() = nativeBitmap.bitmapWidth().toUShort()
    val height: UShort get() = nativeBitmap.bitmapHeight().toUShort()
    val data: ByteArray
        get() {
            val pixels = nativeBitmap.bitmapPixels()
            return pixels.copyToByteArray(pixels.remaining())
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JpegImage) return false
        return nativeBitmap.bitmapEquals(other.nativeBitmap)
    }

    override fun hashCode(): Int = nativeBitmap.bitmapHashCode()

    companion object {
        operator fun invoke(
            width: UShort,
            height: UShort,
            data: ByteArray,
        ): JpegImage = JpegImage(platformBitmapOf(width.toInt(), height.toInt(), data))
    }
}

/**
 * Hand-written `Codec<JpegImage>` referenced by
 * `@UseCodec(JpegImageCodec::class)`. Decode reads width / height as UShort
 * BE then materializes pixel bytes into a consumer-owned [PlatformBuffer]
 * via the factory injected through [BufferFactoryKey] (defaults to
 * [testFixtureFactory]) — the canonical Pattern #2 buffer-to-buffer copy,
 * no intermediate `ByteArray`. Encode walks back via the platform handle's
 * pixel buffer accessor.
 */
object JpegImageCodec : Codec<JpegImage> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): JpegImage {
        val width = buffer.readUnsignedShort().toInt()
        val height = buffer.readUnsignedShort().toInt()
        val factory = context.bufferFactoryOrDefault()
        val pixels = factory.allocate(buffer.remaining())
        pixels.write(buffer)
        pixels.resetForRead()
        return JpegImage(bitmapFrom(width, height, pixels))
    }

    override fun encode(
        buffer: WriteBuffer,
        value: JpegImage,
        context: EncodeContext,
    ) {
        buffer.writeUShort(value.width)
        buffer.writeUShort(value.height)
        buffer.write(value.nativeBitmap.bitmapPixels())
    }

    override fun wireSize(
        value: JpegImage,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(4 + value.nativeBitmap.bitmapPixelSize())
}
