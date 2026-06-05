package com.ditchoom.buffer.codec.test.protocols.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.VarLenPeek
import com.ditchoom.buffer.codec.VariableLengthCodec
import com.ditchoom.buffer.stream.StreamProcessor

/**
 * QUIC variable-length integer codec — RFC 9000 §16. **Test-support only**:
 * the buffer library ships no protocol encodings; this lives in the test
 * module to exercise the generic [VariableLengthCodec] plumbing on a real
 * self-delimiting encoding.
 *
 * The two most-significant bits of the first byte name the length class — 1, 2,
 * 4, or 8 bytes — and the remaining 6/14/30/62 bits carry the value big-endian:
 *
 * ```text
 *   2MSB  bytes  usable bits  max value
 *   00      1       6         2^6  - 1 = 63
 *   01      2      14         2^14 - 1 = 16383
 *   10      4      30         2^30 - 1
 *   11      8      62         2^62 - 1
 * ```
 *
 * Encoding is minimal (smallest length class that fits). Decoding accepts
 * non-minimal encodings per spec (e.g. `0x40 0x25` → 37), so a decode of
 * non-minimal input followed by a re-encode is *not* byte-identical — the
 * value round-trips, the bytes do not.
 */
object QuicVarintCodec : VariableLengthCodec<ULong> {
    /** Largest value a QUIC varint can represent: 2^62 - 1. */
    const val MAX_VALUE: ULong = 0x3FFF_FFFF_FFFF_FFFFuL

    private const val ONE_BYTE_MAX: ULong = 0x3FuL // 2^6 - 1
    private const val TWO_BYTE_MAX: ULong = 0x3FFFuL // 2^14 - 1
    private const val FOUR_BYTE_MAX: ULong = 0x3FFF_FFFFuL // 2^30 - 1

    override fun encodedLength(value: ULong): Int =
        when {
            value <= ONE_BYTE_MAX -> 1
            value <= TWO_BYTE_MAX -> 2
            value <= FOUR_BYTE_MAX -> 4
            value <= MAX_VALUE -> 8
            else -> throw IllegalArgumentException(
                "value $value exceeds the QUIC varint range 0..$MAX_VALUE (RFC 9000 §16)",
            )
        }

    override fun encode(
        buffer: WriteBuffer,
        value: ULong,
        context: EncodeContext,
    ) {
        val length = encodedLength(value)
        // Length class encoded in the 2 high bits of byte 0: log2(length).
        val lengthClass =
            when (length) {
                1 -> 0
                2 -> 1
                4 -> 2
                else -> 3
            }
        for (i in 0 until length) {
            val shift = (length - 1 - i) * 8
            var b = ((value shr shift) and 0xFFuL).toInt()
            // The value occupies length*8 - 2 bits, so byte 0's top 2 bits are
            // free for the length class.
            if (i == 0) b = b or (lengthClass shl 6)
            buffer.writeByte(b.toByte())
        }
    }

    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): ULong {
        val first = buffer.readUByte().toInt()
        val length = 1 shl (first shr 6)
        var value = (first and 0x3F).toULong()
        for (i in 1 until length) {
            value = (value shl 8) or buffer.readUByte().toULong()
        }
        return value
    }

    override fun peekValue(
        stream: StreamProcessor,
        baseOffset: Int,
    ): VarLenPeek<ULong> {
        if (stream.available() - baseOffset < 1) return VarLenPeek.NeedsMoreData
        val first = stream.peekByte(baseOffset).toInt() and 0xFF
        val length = 1 shl (first shr 6)
        if (stream.available() - baseOffset < length) return VarLenPeek.NeedsMoreData
        var value = (first and 0x3F).toULong()
        for (i in 1 until length) {
            value = (value shl 8) or (stream.peekByte(baseOffset + i).toInt() and 0xFF).toULong()
        }
        return VarLenPeek.Decoded(value, length)
    }
}
