package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.BufferFactoryKey
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.OpaqueBytesHandle
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.asReadBuffer
import com.ditchoom.buffer.codec.byteSize
import com.ditchoom.buffer.codec.handleEquals
import com.ditchoom.buffer.codec.opaqueBytesFrom
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.jvm.JvmInline

/**
 * Minimal `Payload`-marked typed binary-data wrapper. Reshaped under the
 * buffer-codec lockdown v1 to satisfy the transitive Payload-shape rule
 * (Change 1): the inner storage is an [OpaqueBytesHandle], not a raw
 * `ByteArray`. The handle's actuals hold a consumer-owned `PlatformBuffer`
 * (allocated via the caller-supplied factory through [BufferFactoryKey],
 * defaulting to [testFixtureFactory]) — the KSP walker treats the handle
 * as opaque and stops there.
 *
 * **Test ergonomics**: use [opaqueBytesOf] to construct from a `ByteArray`
 * literal, and [OpaqueBytesHandle.toBytes] to materialize back via
 * `copyToByteArray` for content assertions. Both helpers live alongside
 * [OpaqueBytesHandle].
 */
@JvmInline
value class BinaryData(
    val data: OpaqueBytesHandle,
) : Payload {
    /**
     * Test convenience: materializes the underlying bytes as a heap
     * `ByteArray` (via `copyToByteArray`). Allocates on each read —
     * production code should prefer [OpaqueBytesHandle.asReadBuffer] for
     * the zero-copy view, or [OpaqueBytesHandle.handleEquals] for content
     * comparison. Defined as an in-class property so call sites resolve
     * `binaryData.bytes` without a separate extension-property import.
     */
    val bytes: ByteArray get() = data.toBytes()

    /** Test convenience: same as `data.byteSize()`. */
    val size: Int get() = data.byteSize()
}

/**
 * Hand-written `Codec<BinaryData>` referenced by `@UseCodec`. Decode reads
 * the bounded region into a consumer-owned [com.ditchoom.buffer.PlatformBuffer]
 * via the canonical Pattern #2 primitive `factory.allocate(...).write(buffer)`
 * — no intermediate `ByteArray` materializes along the decode path. The
 * factory comes from [BufferFactoryKey] on the [DecodeContext], or falls
 * back to [testFixtureFactory].
 *
 * `peekFrameSize` returns `NoFraming` — framing is owned by the outer
 * message's codec which walks the prefix in its own `peekFrameSize`.
 */
object BinaryDataCodec : Codec<BinaryData> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): BinaryData {
        val factory = context.bufferFactoryOrDefault()
        val dst = factory.allocate(buffer.remaining())
        dst.write(buffer)
        dst.resetForRead()
        return BinaryData(opaqueBytesFrom(dst))
    }

    override fun encode(
        buffer: WriteBuffer,
        value: BinaryData,
        context: EncodeContext,
    ) {
        buffer.write(value.data.asReadBuffer())
    }

    override fun wireSize(
        value: BinaryData,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(value.data.byteSize())

    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult = PeekResult.NoFraming
}

/**
 * Test convenience: construct [BinaryData] directly from a `ByteArray`
 * literal. Wraps via [opaqueBytesOf] (which uses [testFixtureFactory]).
 */
fun BinaryData(bytes: ByteArray): BinaryData = BinaryData(opaqueBytesOf(bytes))
