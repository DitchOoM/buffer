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

    override fun encodedLength(value: UInt): Int {
        var v = value
        var len = 1
        while (v >= 0x80u) {
            v = v shr 7
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
        while (v >= 0x80u) {
            buffer.writeByte(((v and 0x7Fu) or 0x80u).toByte())
            v = v shr 7
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
            result = result or ((b and 0x7F).toUInt() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
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
            result = result or ((b and 0x7F).toUInt() shl shift)
            i++
            if (b and 0x80 == 0) break
            shift += 7
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
