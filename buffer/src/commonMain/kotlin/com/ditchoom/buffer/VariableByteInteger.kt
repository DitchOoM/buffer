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
 * Writes a variable-byte-integer length prefix followed by [encode]'s output.
 *
 * Encodes the body into a scratch buffer first, then writes the canonical VBI prefix
 * and copies the scratch contents into the host buffer. The scratch sizing follows the
 * host's remaining bytes (with a small floor) — the host's remaining bytes equal
 * `actualVbiWidth + bodyLen` for tightly-sized callers like mqtt's `packetSize()`-based
 * serialize, so body always fits in `remaining` bytes.
 *
 * The scratch indirection avoids the otherwise-tempting "reserve [maxBytes] in place,
 * encode body, shift" optimization, which would require the host buffer to have
 * `maxBytes + bodyLen` free instead of `actualVbiWidth + bodyLen`. That extra `maxBytes
 * - actualVbiWidth` overhead conflicts with callers that size buffers to exact wire
 * length.
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
    val scratchSize = remaining().coerceAtLeast(16)
    val scratch = BufferFactory.Default.allocate(scratchSize, byteOrder)
    encode(scratch)
    scratch.resetForRead()
    val len = scratch.remaining()
    val maxVal = variableByteMax(cap)
    require(len in 0..maxVal) {
        val field = if (fieldName.isEmpty()) "" else "field '$fieldName' "
        "${field}encoded length $len exceeds maxBytes=$cap (max value $maxVal)"
    }
    writeVariableByteInteger(len)
    if (len > 0) write(scratch)
}
