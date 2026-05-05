package com.ditchoom.buffer.codec.test.protocols.usecodecscalar

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.BoundingLengthCodec
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.UseCodec

/**
 * Phase I.1 step 4 doctrine vector — exercises bare `@UseCodec val:
 * <scalar>` (no framing annotation) against:
 *   - a plain `Codec<UInt>` user codec ([ZigZagUIntCodec]) — non-bounding
 *     emit path: `decode` reads via the codec, `encode` writes via the
 *     codec, `wireSize` collapses to `WireSize.BackPatch`,
 *     `peekFrameSize` to `PeekResult.NoFraming`. No try/finally wrapping.
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

    override fun wireSize(
        value: UInt,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(4)
}

/**
 * `BoundingLengthCodec<UInt>` reading/writing a fixed-width 4-byte
 * little-endian length and narrowing `buffer.limit()` to bound the
 * subsequent decode region. Deliberately uses little-endian so the
 * framework's natural-width big-endian read can't accidentally
 * substitute correctly.
 */
object Le32LengthCodec : BoundingLengthCodec<UInt> {
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

    override fun wireSize(
        value: UInt,
        context: EncodeContext,
    ): WireSize = WireSize.Exact(4)

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
 * payload (length bytes, scalars)]`. Exercises the bounding emit
 * path:
 *   1. `decode` reads `tag`, captures `__lengthOuterLimit =
 *      buffer.limit()`, reads `length` via `Le32LengthCodec.decode`,
 *      calls `Le32LengthCodec.applyBound(buffer, length)` (sets
 *      limit = position + length).
 *   2. `payload` decode runs inside the bounded buffer
 *      (`@RemainingBytes List<Byte>` reads until the new limit).
 *   3. `try { ... } finally { buffer.setLimit(__lengthOuterLimit) }`
 *      restores the caller's outer limit even if payload decode
 *      throws.
 *
 * Validates that decoding a buffer with TRAILING bytes past the
 * frame still produces the correct payload size — the user codec's
 * `applyBound` is what bounds the read, not the buffer's natural
 * remaining bytes.
 */
@ProtocolMessage
data class BoundedFrame(
    val tag: Short,
    @UseCodec(Le32LengthCodec::class) val length: UInt,
    @RemainingBytes val payload: List<Byte>,
)
