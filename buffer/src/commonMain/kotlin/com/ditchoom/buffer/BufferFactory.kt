package com.ditchoom.buffer

import kotlin.math.roundToInt

/** Largest code point encoded as a single UTF-8 byte (`0x7F`). */
private const val UTF8_ONE_BYTE_MAX = 0x7F

/** Largest code point encoded as two UTF-8 bytes (`0x7FF`). */
private const val UTF8_TWO_BYTE_MAX = 0x7FF

/** UTF-8 byte count for a code point that requires three bytes. */
private const val UTF8_THREE_BYTES = 3

/** UTF-8 byte count for a supplementary code point (surrogate pair → four bytes). */
private const val UTF8_FOUR_BYTES = 4

fun CharSequence.maxBufferSize(charset: Charset): Int = (charset.maxBytesPerChar * this.length).roundToInt()

fun String.toReadBuffer(
    charset: Charset = Charset.UTF8,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    if (this == "") {
        return ReadBuffer.EMPTY_BUFFER
    }
    val maxBytes = maxBufferSize(charset)
    val buffer = factory.allocate(maxBytes)
    buffer.writeString(this, charset)
    buffer.resetForRead()
    return buffer.slice()
}

fun CharSequence.utf8Length(): Int {
    var count = 0
    var i = 0
    val len = length
    while (i < len) {
        val ch = get(i)
        if (ch.code <= UTF8_ONE_BYTE_MAX) {
            count++
        } else if (ch.code <= UTF8_TWO_BYTE_MAX) {
            count += 2
        } else if (ch >= '\uD800' && ch.code < '\uDBFF'.code + 1) {
            count += UTF8_FOUR_BYTES
            ++i
        } else {
            count += UTF8_THREE_BYTES
        }
        i++
    }
    return count
}
