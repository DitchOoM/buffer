package com.ditchoom.buffer.codec

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * Unsigned LEB128 variable-length integer codec for [UInt] — the classic 7-bits-per-byte,
 * high-bit-as-continuation encoding (protobuf varint, DWARF LEB128). A self-delimiting
 * [VariableLengthCodec], so it reports `Exact(encodedLength)` at runtime (no BackPatch) and
 * frames a stream via [peekValue].
 *
 * ```text
 *  value range          bytes
 *  0 .. 2^7-1   (127)     1
 *  .. 2^14-1              2
 *  .. 2^21-1              3
 *  .. 2^28-1              4
 *  .. 2^32-1             5   (the 5th byte carries only the top 4 bits)
 * ```
 *
 * Each byte stores 7 value bits (little-endian group order); the high bit (`0x80`) is set on
 * every byte except the last. Encoding is minimal. Decoding rejects an over-long sequence (a
 * value that cannot fit in [UInt], or a 6th continuation byte) with [DecodeException] so a
 * truncated/malicious stream can never loop or overflow.
 *
 * This is the library's first shipped self-delimiting integer encoding — used by the generated
 * enum-discriminator codec (a `@ProtocolMessage` enum field's ordinal rides as an
 * `UnsignedVarIntCodec` value), and available to consumers via `@UseCodec(UnsignedVarIntCodec::class)`.
 */
object UnsignedVarIntCodec : VariableLengthCodec<UInt> {
    /** Maximum encoded length of a 32-bit value: ceil(32 / 7) = 5 bytes. */
    const val MAX_BYTES: Int = 5

    /** Number of value bits carried per LEB128 byte (the low 7 bits). */
    private const val VALUE_BITS_PER_BYTE = 7

    /** Mask selecting the 7 value bits of a LEB128 byte. */
    private const val VALUE_MASK = 0x7F

    /** High bit of a LEB128 byte: set on every byte except the last (continuation flag). */
    private const val CONTINUATION_BIT = 0x80

    /**
     * High value bits (4..6) of the 5th byte: these would carry value bits 32..34,
     * which cannot fit in a [UInt], so a non-zero result signals overflow.
     */
    private const val FIFTH_BYTE_OVERFLOW_MASK = 0x70

    private const val CONTINUATION_BIT_U = 0x80u
    private const val VALUE_MASK_U = 0x7Fu

    override fun encodedLength(value: UInt): Int {
        var v = value
        var len = 1
        while (v >= CONTINUATION_BIT_U) {
            v = v shr VALUE_BITS_PER_BYTE
            len++
        }
        return len
    }

    override fun encode(
        buffer: WriteBuffer,
        value: UInt,
        context: EncodeContext,
    ) {
        var v = value
        while (v >= CONTINUATION_BIT_U) {
            buffer.writeByte(((v and VALUE_MASK_U) or CONTINUATION_BIT_U).toByte())
            v = v shr VALUE_BITS_PER_BYTE
        }
        buffer.writeByte(v.toByte())
    }

    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): UInt {
        var result = 0u
        var shift = 0
        var bytes = 0
        while (true) {
            val b = buffer.readUByte().toInt()
            bytes++
            // On the 5th (last possible) byte only the low 4 bits are in UInt range; bits 4..6
            // (0x70) would carry value bits 32..34 and silently truncate, so reject the overflow.
            if (bytes == MAX_BYTES && b and FIFTH_BYTE_OVERFLOW_MASK != 0) {
                throw DecodeException(
                    fieldPath = "UnsignedVarInt",
                    bufferPosition = buffer.position(),
                    expected = "a 5th byte within UInt range (high bits 0x70 clear)",
                    actual = "a 5th byte carrying value bits beyond 2^32",
                )
            }
            result = result or ((b and VALUE_MASK).toUInt() shl shift)
            if (b and CONTINUATION_BIT == 0) break
            shift += VALUE_BITS_PER_BYTE
            if (bytes >= MAX_BYTES) {
                throw DecodeException(
                    fieldPath = "UnsignedVarInt",
                    bufferPosition = buffer.position(),
                    expected = "a terminating byte within $MAX_BYTES bytes (UInt range)",
                    actual = "a $MAX_BYTES-byte sequence still requesting continuation",
                )
            }
        }
        return result
    }

    override fun peekValue(
        stream: StreamProcessor,
        baseOffset: Int,
    ): VarLenPeek<UInt> {
        var result = 0u
        var shift = 0
        var i = 0
        while (true) {
            if (stream.available() - baseOffset < i + 1) return VarLenPeek.NeedsMoreData
            val b = stream.peekByte(baseOffset + i).toInt() and 0xFF
            // 5th byte: reject value bits beyond 2^32 (see decode) rather than truncate.
            if (i == MAX_BYTES - 1 && b and FIFTH_BYTE_OVERFLOW_MASK != 0) {
                throw DecodeException(
                    fieldPath = "UnsignedVarInt",
                    bufferPosition = baseOffset + i,
                    expected = "a 5th byte within UInt range (high bits 0x70 clear)",
                    actual = "a 5th byte carrying value bits beyond 2^32",
                )
            }
            result = result or ((b and VALUE_MASK).toUInt() shl shift)
            i++
            if (b and CONTINUATION_BIT == 0) break
            shift += VALUE_BITS_PER_BYTE
            if (i >= MAX_BYTES) {
                throw DecodeException(
                    fieldPath = "UnsignedVarInt",
                    bufferPosition = baseOffset + i,
                    expected = "a terminating byte within $MAX_BYTES bytes (UInt range)",
                    actual = "a $MAX_BYTES-byte sequence still requesting continuation",
                )
            }
        }
        return VarLenPeek.Decoded(result, i)
    }
}
