package com.ditchoom.buffer.codec.test.protocols.usecodecscalar

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.BoundingLengthCodec
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryData
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryDataCodec
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * Doctrine vector — exercises bare `@UseCodec val:
 * <scalar>` (no framing annotation) against:
 *   - a plain `Codec<UInt>` user codec ([ZigZagUIntCodec]) — non-bounding
 *     emit path: `decode` reads via the codec, `encode` writes via the
 *     codec, `wireBytes` collapses to `WireByteCount.BackPatch`,
 *     `frameSize` to `FrameSize.Unframed`. No try/finally wrapping.
 *   - a `BoundingLengthCodec<UInt>` user codec ([Le32LengthCodec]) — the
 *     bounding emit path: outer limit captured into `__lengthOuterLimit`
 *     before decode, `applyBound` called after decode, subsequent fields
 *     run inside `try { ... } finally { setLimit(__lengthOuterLimit) }`.
 *
 * The framework knows nothing about either codec's wire shape — the
 * only thing it inspects is the supertype chain (presence of
 * `BoundingLengthCodec` flips the `isBounding` bit on `FieldSpec.UseCodecScalar`).
 *
 * Plain `Codec<UInt>`. Reads/writes a UInt with a deliberately
 * non-natural wire shape (zig-zag-encoded 4 bytes) to prove the
 * framework genuinely delegates to the user codec rather than
 * substituting a built-in `readUInt`. Wire = 4 BE bytes carrying the
 * zig-zag-encoded value.
 */
object ZigZagUIntCodec : Codec<UInt> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): UInt {
        val raw = buffer.readInt()
        val zigzagged = (raw ushr 1) xor (-(raw and 1))
        return zigzagged.toUInt()
    }

    override fun encode(
        buffer: WriteBuffer,
        value: UInt,
        context: EncodeContext,
    ) {
        val v = value.toInt()
        val zig = (v shl 1) xor (v shr 31)
        buffer.writeInt(zig)
    }

    override fun wireBytes(
        value: UInt,
        context: EncodeContext,
    ): WireByteCount = WireByteCount.exact(4)
}

/**
 * `BoundingLengthCodec<UInt>` reading/writing a fixed-width 4-byte
 * little-endian length and narrowing `buffer.limit()` to bound the
 * subsequent decode region. Deliberately uses little-endian so the
 * framework's natural-width big-endian read can't accidentally
 * substitute correctly.
 */
object Le32LengthCodec : BoundingLengthCodec<UInt> {
    override val maxWireSize: Int = 4

    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): UInt {
        val b0 = buffer.readByte().toInt() and 0xFF
        val b1 = buffer.readByte().toInt() and 0xFF
        val b2 = buffer.readByte().toInt() and 0xFF
        val b3 = buffer.readByte().toInt() and 0xFF
        return (b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)).toUInt()
    }

    override fun encode(
        buffer: WriteBuffer,
        value: UInt,
        context: EncodeContext,
    ) {
        val v = value.toInt()
        buffer.writeByte((v and 0xFF).toByte())
        buffer.writeByte(((v ushr 8) and 0xFF).toByte())
        buffer.writeByte(((v ushr 16) and 0xFF).toByte())
        buffer.writeByte(((v ushr 24) and 0xFF).toByte())
    }

    override fun wireBytes(
        value: UInt,
        context: EncodeContext,
    ): WireByteCount = WireByteCount.exact(4)

    override fun peekLength(
        stream: StreamProcessor,
        baseOffset: Int,
    ): FrameSize {
        if (stream.available() - baseOffset < 4) return FrameSize.NeedsMoreData
        var value = 0L
        for (i in 3 downTo 0) value = (value shl 8) or (stream.peekByte(baseOffset + i).toLong() and 0xFF)
        return FrameSize.declared(4, value)
    }

    override fun encodedWidth(bodyBytes: Long): WireByteCount = WireByteCount.exact(4)

    override fun applyBound(
        buffer: ReadBuffer,
        decodedValue: UInt,
    ) {
        buffer.setLimit(buffer.position() + decodedValue.toInt())
    }
}

/**
 * Wire layout `[id BE Int | encoded UInt via ZigZagUIntCodec]`.
 * Exercises the non-bounding emit path: the framework calls
 * `ZigZagUIntCodec.decode/encode` for `value` but doesn't wrap any
 * surrounding fields in try/finally.
 */
@ProtocolMessage
data class ZigZagFrame(
    val id: Int,
    @UseCodec(ZigZagUIntCodec::class) val value: UInt,
)

/**
 * Wire layout `[tag BE Short | length 4-byte LE via Le32LengthCodec |
 * payload (length bytes, opaque)]`. Exercises the bounding emit
 * path:
 *   1. `decode` reads `tag`, captures `__lengthOuterLimit =
 *      buffer.limit()`, reads `length` via `Le32LengthCodec.decode`,
 *      calls `Le32LengthCodec.applyBound(buffer, length)` (sets
 *      limit = position + length).
 *   2. `payload` decode runs inside the bounded buffer
 *      (`@RemainingBytes @UseCodec(BinaryDataCodec::class) val payload:
 *      BinaryData` bulk-reads bytes until the new limit).
 *   3. `try { ... } finally { buffer.setLimit(__lengthOuterLimit) }`
 *      restores the caller's outer limit even if payload decode
 *      throws.
 *
 * Validates that decoding a buffer with TRAILING bytes past the
 * frame still produces the correct payload size — the user codec's
 * `applyBound` is what bounds the read, not the buffer's natural
 * remaining bytes.
 *
 * Retyped `payload` from `List<Byte>` to
 * `BinaryData` to retire the `@RemainingBytes List<scalar>` shape.
 * The `@RemainingBytes @UseCodec(C::class) val: P` path
 * carries the same bounding semantics with explicit Payload-codec
 * memory ownership.
 */
@ProtocolMessage
data class BoundedFrame(
    val tag: Short,
    @UseCodec(Le32LengthCodec::class) val length: UInt,
    @RemainingBytes @UseCodec(BinaryDataCodec::class) val payload: BinaryData,
)

/** `BoundingLengthCodec<ULong>` over a raw 8-byte big-endian length: reaches the full u64 range. */
object Be64LengthCodec : BoundingLengthCodec<ULong> {
    override val maxWireSize: Int = 8

    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): ULong = buffer.readLong().toULong()

    override fun encode(
        buffer: WriteBuffer,
        value: ULong,
        context: EncodeContext,
    ) {
        buffer.writeLong(value.toLong())
    }

    override fun wireBytes(
        value: ULong,
        context: EncodeContext,
    ): WireByteCount = WireByteCount.exact(8)

    override fun peekLength(
        stream: StreamProcessor,
        baseOffset: Int,
    ): FrameSize {
        if (stream.available() - baseOffset < 8) return FrameSize.NeedsMoreData
        var value = 0uL
        for (i in 0 until 8) value = (value shl 8) or (stream.peekByte(baseOffset + i).toULong() and 0xFFuL)
        return FrameSize.declared(8, value)
    }

    override fun encodedWidth(bodyBytes: Long): WireByteCount = WireByteCount.exact(8)

    override fun applyBound(
        buffer: ReadBuffer,
        decodedValue: ULong,
    ) {
        buffer.setLimit(buffer.position() + decodedValue.toInt())
    }
}

/** `BoundingLengthCodec<Int>` over a signed 4-byte big-endian length: can declare a negative. */
object SignedBe32LengthCodec : BoundingLengthCodec<Int> {
    override val maxWireSize: Int = 4

    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): Int = buffer.readInt()

    override fun encode(
        buffer: WriteBuffer,
        value: Int,
        context: EncodeContext,
    ) {
        buffer.writeInt(value)
    }

    override fun wireBytes(
        value: Int,
        context: EncodeContext,
    ): WireByteCount = WireByteCount.exact(4)

    override fun peekLength(
        stream: StreamProcessor,
        baseOffset: Int,
    ): FrameSize {
        if (stream.available() - baseOffset < 4) return FrameSize.NeedsMoreData
        var value = 0
        for (i in 0 until 4) value = (value shl 8) or (stream.peekByte(baseOffset + i).toInt() and 0xFF)
        return FrameSize.declared(4, value.toLong()) // a negative declaration is refused here
    }

    override fun encodedWidth(bodyBytes: Long): WireByteCount = WireByteCount.exact(4)

    override fun applyBound(
        buffer: ReadBuffer,
        decodedValue: Int,
    ) {
        buffer.setLimit(buffer.position() + decodedValue)
    }
}

/** Wire layout `[tag BE Short | length 8-byte BE u64 | payload]`. */
@ProtocolMessage
data class U64BoundedFrame(
    val tag: Short,
    @UseCodec(Be64LengthCodec::class) val length: ULong,
    @RemainingBytes @UseCodec(BinaryDataCodec::class) val payload: BinaryData,
)

/** Wire layout `[tag BE Short | length signed 4-byte BE | payload]`. */
@ProtocolMessage
data class SignedBoundedFrame(
    val tag: Short,
    @UseCodec(SignedBe32LengthCodec::class) val length: Int,
    @RemainingBytes @UseCodec(BinaryDataCodec::class) val payload: BinaryData,
)
