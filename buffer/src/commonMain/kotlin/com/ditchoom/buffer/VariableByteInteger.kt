package com.ditchoom.buffer

import kotlin.experimental.and
import kotlin.experimental.or

/**
 * Maximum value encodable as a variable-byte integer (4 bytes): 268,435,455.
 */
const val VARIABLE_BYTE_INT_MAX = 268435455

/**
 * Encodes [value] as a variable-byte integer (1–4 bytes).
 *
 * Used by MQTT, HTTP/2 HPACK, and other binary protocols.
 *
 * @throws IllegalArgumentException if [value] is outside 0..[VARIABLE_BYTE_INT_MAX]
 */
fun WriteBuffer.writeVariableByteInteger(value: Int): WriteBuffer {
    require(value in 0..VARIABLE_BYTE_INT_MAX) {
        "Variable byte integer value $value is out of range (0..$VARIABLE_BYTE_INT_MAX)"
    }
    var numBytes = 0
    var remaining = value.toLong()
    do {
        var digit = (remaining % 128).toByte()
        remaining /= 128
        if (remaining > 0) {
            digit = digit or 0x80.toByte()
        }
        writeByte(digit)
        numBytes++
    } while (remaining > 0 && numBytes < 4)
    return this
}

/**
 * Decodes a variable-byte integer (1–4 bytes) from this buffer.
 *
 * @throws IllegalArgumentException if the encoded value is malformed or exceeds [VARIABLE_BYTE_INT_MAX]
 */
fun ReadBuffer.readVariableByteInteger(): Int {
    var digit: Byte
    var value = 0L
    var multiplier = 1L
    var count = 0
    do {
        if (count >= 4) {
            throw IllegalArgumentException(
                "Variable byte integer is malformed: more than 4 continuation bytes",
            )
        }
        if (remaining() < 1) {
            throw IllegalArgumentException(
                "Variable byte integer is malformed: unexpected end of buffer after $count byte(s)",
            )
        }
        digit = readByte()
        count++
        value += (digit and 0x7F).toLong() * multiplier
        multiplier *= 128
    } while ((digit and 0x80.toByte()).toInt() != 0)
    require(value in 0..VARIABLE_BYTE_INT_MAX.toLong()) {
        "Variable byte integer value $value is out of range (0..$VARIABLE_BYTE_INT_MAX)"
    }
    return value.toInt()
}

/**
 * Convenience wrapper returning Int instead of Byte, for use in codec sizeOf computations.
 */
fun variableByteSizeInt(value: Int): Int = variableByteSize(value).toInt()

/**
 * Returns the number of bytes (1–4) needed to encode [value] as a variable-byte integer.
 *
 * @throws IllegalArgumentException if [value] is outside 0..[VARIABLE_BYTE_INT_MAX]
 */
fun variableByteSize(value: Int): Byte {
    require(value in 0..VARIABLE_BYTE_INT_MAX) {
        "Variable byte integer value $value is out of range (0..$VARIABLE_BYTE_INT_MAX)"
    }
    var numBytes: Byte = 0
    var remaining = value
    do {
        remaining /= 128
        numBytes++
    } while (remaining > 0 && numBytes < 4)
    return numBytes
}

/**
 * Maximum body length encodable with a VBI capped at [maxBytes] bytes (1..4).
 *   maxBytes=1 → 127           (0x7F)
 *   maxBytes=2 → 16383         (0x3FFF)
 *   maxBytes=3 → 2097151       (0x1FFFFF)
 *   maxBytes=4 → 268435455     (0xFFFFFFF, [VARIABLE_BYTE_INT_MAX])
 */
fun variableByteMax(maxBytes: Int): Int {
    require(maxBytes in 1..4) { "maxBytes must be 1..4, got $maxBytes" }
    return (1 shl (maxBytes * 7)) - 1
}

/**
 * Writes a variable-byte-integer length prefix followed by [encode]'s output, with
 * zero-allocation patch-up: reserves [maxBytes] bytes, encodes the body, measures the
 * actual length, writes the canonical (smallest-width) VBI prefix, and shifts the body
 * left if the canonical width is shorter than the reservation.
 *
 * `0` for [maxBytes] is treated as `4` (the spec cap on VBI).
 *
 * @throws IllegalArgumentException if the encoded body length exceeds the cap implied
 *   by [maxBytes]. The message includes [fieldName] and the offending length.
 */
fun WriteBuffer.writeVariableByteIntegerLengthPrefixed(
    maxBytes: Int = 4,
    fieldName: String = "",
    encode: (buffer: WriteBuffer) -> Unit,
) {
    val cap = if (maxBytes == 0) 4 else maxBytes
    require(cap in 1..4) { "maxBytes must be 0..4, got $maxBytes" }
    // The buffer must be readable (PlatformBuffer) to support the in-place shift.
    val pb =
        this as? PlatformBuffer
            ?: error(
                "writeVariableByteIntegerLengthPrefixed requires a PlatformBuffer (read+write). " +
                    "Got ${this::class.simpleName}.",
            )
    val pos = pb.position()
    repeat(cap) { pb.writeByte(0.toByte()) }
    encode(pb)
    val end = pb.position()
    val len = end - pos - cap
    val maxVal = variableByteMax(cap)
    require(len in 0..maxVal) {
        val field = if (fieldName.isEmpty()) "" else "field '$fieldName' "
        "${field}encoded length $len exceeds maxBytes=$cap (max value $maxVal)"
    }
    val vbi = variableByteSize(len).toInt()
    if (vbi < cap) {
        // Shift body left to close the gap left by the reservation.
        for (i in 0 until len) {
            pb[pos + vbi + i] = pb[pos + cap + i]
        }
    }
    pb.position(pos)
    pb.writeVariableByteInteger(len)
    pb.position(pos + vbi + len)
}
