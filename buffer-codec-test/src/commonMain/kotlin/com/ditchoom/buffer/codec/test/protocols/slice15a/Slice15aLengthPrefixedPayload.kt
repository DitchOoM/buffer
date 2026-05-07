package com.ditchoom.buffer.codec.test.protocols.slice15a

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.jvm.JvmInline

/**
 * Phase J.M.5 slice 15a — capability probe for `@LengthPrefixed
 * @UseCodec(C::class) val: T : Payload`. Scalar counterpart of slice
 * 11's `@LengthPrefixed @UseCodec val: List<E>`.
 *
 * Wire shape: a fixed-width unsigned-int prefix (default 2 bytes /
 * UShort BE) followed by exactly that many body bytes consumed by `C`.
 * The framework owns the prefix; `C` is a `Codec<T>` (NOT a
 * `BoundingLengthCodec`) — the prefix tells the framework how many
 * bytes to hand to `C`.
 *
 * `T : Payload` is required by the validator (slice 15 D2 — clusters
 * the "typed binary data crossing the codec boundary" concept under
 * one marker).
 *
 * This probe ships before slice 15c/15d retypes any v5 fixture so the
 * generic emitter capability has a focused fixture exercising it
 * before production-shaped fixtures (CONNECT will-payload + password,
 * MqttV5Property.{CorrelationData, AuthenticationData}) compose with
 * the dispatcher / property-bag / `@When` shapes downstream.
 */
@ProtocolMessage
data class Slice15aLengthPrefixedPayload(
    @LengthPrefixed @UseCodec(BinaryDataCodec::class) val data: BinaryData,
)

/**
 * Minimal `Payload`-marked typed binary-data wrapper. Slices 15c and
 * 15d reuse this type for the v5 property variants (CorrelationData /
 * AuthenticationData) and the v3/v5 CONNECT will-payload + password
 * fields respectively.
 *
 * `@JvmInline value class` over `ByteArray` matches the design notes —
 * downstream consumers wrap their own bytes in a similar value class,
 * defining ownership semantics inside the codec author's `Codec<T>`.
 * `ByteArray` reference equality means tests must compare
 * `decoded.data.bytes.contentEquals(...)` instead of `decoded ==
 * original` directly.
 */
@JvmInline
value class BinaryData(
    val bytes: ByteArray,
) : Payload

/**
 * Hand-written `Codec<BinaryData>` referenced by `@UseCodec`. Decode
 * consumes the bounded region (the framework narrows `buffer.limit()`
 * to position + length-prefix-value before calling `decode`); encode
 * just writes the bytes. wireSize is `Exact(bytes.size)` — the user
 * codec owns body sizing — but the containing message's wireSize
 * still collapses to BackPatch because the slice 15a emit can't
 * forward through the `@LengthPrefixed` framing without extra
 * machinery (same conservative collapse as slices 10a/10b/11).
 *
 * `peekFrameSize` returns `NoFraming` — the framing is owned by the
 * outer message's codec (slice 15a), which walks the prefix in its
 * own `peekFrameSize`. The user codec runs only inside an already-
 * bounded buffer.
 */
object BinaryDataCodec : Codec<BinaryData> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): BinaryData = BinaryData(buffer.readByteArray(buffer.remaining()))

    override fun encode(
        buffer: WriteBuffer,
        value: BinaryData,
        context: EncodeContext,
    ) {
        buffer.writeBytes(value.bytes)
    }

    override fun wireSize(
        value: BinaryData,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(value.bytes.size)

    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult = PeekResult.NoFraming
}
