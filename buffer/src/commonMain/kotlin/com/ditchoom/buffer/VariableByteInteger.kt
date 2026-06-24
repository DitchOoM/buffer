package com.ditchoom.buffer

import kotlin.experimental.and
import kotlin.experimental.or

/**
 * Maximum value encodable as a variable-byte integer (4 bytes): 268,435,455.
 */
const val VARIABLE_BYTE_INT_MAX = 268435455

/** Radix of each variable-byte-integer digit (7 data bits per byte → base 128). */
private const val VBI_RADIX = 128

/** Continuation flag set on a byte when more variable-byte-integer digits follow (`0x80`). */
private const val VBI_CONTINUATION_FLAG = 0x80

/** Mask isolating the 7 data bits of a variable-byte-integer digit (`0x7F`). */
private const val VBI_DATA_MASK = 0x7F

/** Maximum number of bytes in a variable-byte integer. */
private const val VBI_MAX_BYTES = 4

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
        var digit = (remaining % VBI_RADIX).toByte()
        remaining /= VBI_RADIX
        if (remaining > 0) {
            digit = digit or VBI_CONTINUATION_FLAG.toByte()
        }
        writeByte(digit)
        numBytes++
    } while (remaining > 0 && numBytes < VBI_MAX_BYTES)
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
        if (count >= VBI_MAX_BYTES) {
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
        value += (digit and VBI_DATA_MASK.toByte()).toLong() * multiplier
        multiplier *= VBI_RADIX
    } while ((digit and VBI_CONTINUATION_FLAG.toByte()).toInt() != 0)
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
        remaining /= VBI_RADIX
        numBytes++
    } while (remaining > 0 && numBytes < VBI_MAX_BYTES)
    return numBytes
}
