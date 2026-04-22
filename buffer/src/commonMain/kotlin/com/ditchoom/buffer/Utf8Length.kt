package com.ditchoom.buffer

/**
 * Returns the number of bytes this string would occupy in UTF-8 without
 * actually encoding it. Use when callers only need the size (e.g. to
 * compute a frame length or wire prefix) and would otherwise call
 * [String.encodeToByteArray] purely to read `.size`.
 *
 * Walks the string once; counts 1/2/3 bytes for BMP code points and 4
 * bytes for surrogate pairs. Valid UTF-16 input produces a count
 * identical to `encodeToByteArray().size`. Invalid UTF-16 (unpaired
 * surrogates) is counted as the 3-byte U+FFFD replacement character —
 * this can diverge from `encodeToByteArray().size` on platforms whose
 * Kotlin stdlib substitutes a 1-byte `?` instead. Real WebSocket close
 * reasons, HTTP headers, and MQTT topics are always valid UTF-16, so
 * the primitive's contract targets that case.
 */
fun String.utf8ByteCount(): Int {
    var total = 0
    var i = 0
    val n = length
    while (i < n) {
        val c = this[i].code
        total +=
            when {
                c < 0x80 -> 1
                c < 0x800 -> 2
                c in 0xD800..0xDBFF -> {
                    // High surrogate: pair with following low surrogate.
                    val next = if (i + 1 < n) this[i + 1].code else 0
                    if (next in 0xDC00..0xDFFF) {
                        i++
                        4
                    } else {
                        3 // unpaired → U+FFFD (3 bytes)
                    }
                }
                c in 0xDC00..0xDFFF -> 3 // unpaired low surrogate → U+FFFD
                else -> 3
            }
        i++
    }
    return total
}
