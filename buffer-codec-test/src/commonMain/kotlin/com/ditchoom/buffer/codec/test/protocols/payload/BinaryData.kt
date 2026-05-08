package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.jvm.JvmInline

/**
 * /15c/15d shared fixture — minimal `Payload`
 * marked typed binary-data wrapper. Originally landed in 's
 * probe (`slice15a/Slice15aLengthPrefixedPayload.kt`); promoted here in
 * so the v5 property variants `CorrelationData` /
 * `AuthenticationData` and the v3/v5 `Connect.willPayload` /
 * `Connect.password` fields can reuse it without coupling production
 * fixtures onto the probe package.
 *
 * `@JvmInline value class` over `ByteArray` — downstream consumers
 * (real MQTT library authors) wrap their own bytes in a similar value
 * class, defining ownership semantics inside the codec author's
 * `Codec<T>`. `ByteArray` reference equality means tests must compare
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
 * still collapses to BackPatch ( / conservative
 * collapse).
 *
 * `peekFrameSize` returns `NoFraming` — framing is owned by the outer
 * message's codec which walks the prefix in its own `peekFrameSize`.
 * The user codec runs only inside an already-bounded buffer.
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
