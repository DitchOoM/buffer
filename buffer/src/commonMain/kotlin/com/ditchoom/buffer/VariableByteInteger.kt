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
