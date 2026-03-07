package com.ditchoom.buffer

import kotlin.math.roundToInt

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
        if (ch.code <= 0x7F) {
            count++
        } else if (ch.code <= 0x7FF) {
            count += 2
        } else if (ch >= '\uD800' && ch.code < '\uDBFF'.code + 1) {
            count += 4
            ++i
        } else {
            count += 3
        }
        i++
    }
    return count
}
