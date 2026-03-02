package com.ditchoom.buffer

/**
 * Writes a length-prefixed UTF-8 string: 2-byte big-endian length prefix followed by UTF-8 data.
 *
 * This encoding is used by MQTT, Java's DataOutputStream, and other binary protocols.
 */
fun WriteBuffer.writeLengthPrefixedUtf8String(string: String): WriteBuffer {
    val sizePosition = position()
    position(sizePosition + UShort.SIZE_BYTES)
    val startStringPosition = position()
    writeString(string, Charset.UTF8)
    val stringLength = (position() - startStringPosition).toUShort()
    set(sizePosition, stringLength)
    return this
}

/**
 * Reads a length-prefixed UTF-8 string: 2-byte big-endian length prefix followed by UTF-8 data.
 *
 * @return a [Pair] of (byte length, decoded string)
 */
fun ReadBuffer.readLengthPrefixedUtf8String(): Pair<Int, String> {
    val length = readUnsignedShort().toInt()
    val decoded = readString(length, Charset.UTF8)
    return Pair(length, decoded)
}
