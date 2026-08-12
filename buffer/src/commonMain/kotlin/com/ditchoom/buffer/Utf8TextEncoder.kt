package com.ditchoom.buffer

/**
 * Common reference implementation of the [TextEncoding] UTF-8 contract: sizing, validation,
 * and substituting encode. This is the single source of truth for the bytes every platform
 * must produce — platform fast paths may override [WriteBuffer.writeText] but MUST match
 * these bytes exactly (pinned by the cross-platform vectors in `TextEncodingTests`).
 *
 * Platform staging encoders cannot serve this role: `String.encodeToByteArray()` substitutes
 * U+FFFD (3 bytes) on Kotlin/Native and JS but `?` (1 byte) on Kotlin/JVM, which would break
 * the size == bytes-written invariant across platforms.
 */
internal object Utf8TextEncoder {
    /** Largest code point encoded as a single UTF-8 byte (`0x7F`). */
    private const val ONE_BYTE_MAX = 0x7F

    /** Largest code point encoded as two UTF-8 bytes (`0x7FF`). */
    private const val TWO_BYTE_MAX = 0x7FF

    /** UTF-8 byte count for a BMP code point above [TWO_BYTE_MAX] — and for U+FFFD itself. */
    private const val THREE_BYTES = 3

    /** UTF-8 byte count for a supplementary code point (surrogate pair). */
    private const val FOUR_BYTES = 4

    /** U+FFFD REPLACEMENT CHARACTER encoded as UTF-8. */
    private val REPLACEMENT = byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte())

    private const val CONTINUATION_MARKER = 0x80
    private const val CONTINUATION_PAYLOAD_MASK = 0x3F
    private const val TWO_BYTE_LEAD = 0xC0
    private const val THREE_BYTE_LEAD = 0xE0
    private const val FOUR_BYTE_LEAD = 0xF0
    private const val CONTINUATION_SHIFT = 6

    /** Payload bit offsets of the 2nd/1st continuation byte in a 4-byte sequence. */
    private const val SECOND_CONTINUATION_SHIFT = CONTINUATION_SHIFT * 2
    private const val FOUR_BYTE_LEAD_SHIFT = CONTINUATION_SHIFT * 3
    private const val SUPPLEMENTARY_BASE = 0x10000
    private const val SURROGATE_SHIFT = 10

    /**
     * Exact byte count of [encodeSubstituting] — the size half of the invariant.
     * Unpaired surrogates cost [THREE_BYTES], the U+FFFD substitution cost.
     */
    fun sizeSubstituting(text: CharSequence): Int {
        var count = 0
        var i = 0
        val len = text.length
        while (i < len) {
            val ch = text[i]
            if (ch.code <= ONE_BYTE_MAX) {
                count++
            } else if (ch.code <= TWO_BYTE_MAX) {
                count += 2
            } else if (ch.isHighSurrogate() && i + 1 < len && text[i + 1].isLowSurrogate()) {
                count += FOUR_BYTES
                ++i
            } else {
                count += THREE_BYTES
            }
            i++
        }
        return count
    }

    /**
     * Index of the first unpaired surrogate in [text], or -1 if the text is well-formed UTF-16.
     * Internal sentinel only — public API surfaces this as [TextOutcome.Malformed].
     */
    fun firstMalformedIndex(text: CharSequence): Int {
        var i = 0
        val len = text.length
        while (i < len) {
            val ch = text[i]
            if (ch.isHighSurrogate() && i + 1 < len && text[i + 1].isLowSurrogate()) {
                i += 2
            } else if (ch.isSurrogate()) {
                return i
            } else {
                i++
            }
        }
        return -1
    }

    /**
     * Encodes [text] as UTF-8, substituting U+FFFD for each unpaired surrogate.
     * Produces exactly [sizeSubstituting] bytes — identical on every platform.
     */
    fun encodeSubstituting(text: CharSequence): ByteArray {
        val out = ByteArray(sizeSubstituting(text))
        var o = 0
        var i = 0
        val len = text.length
        while (i < len) {
            val ch = text[i]
            val code = ch.code
            when {
                code <= ONE_BYTE_MAX -> out[o++] = code.toByte()
                code <= TWO_BYTE_MAX -> {
                    out[o++] = (TWO_BYTE_LEAD or (code shr CONTINUATION_SHIFT)).toByte()
                    out[o++] = (CONTINUATION_MARKER or (code and CONTINUATION_PAYLOAD_MASK)).toByte()
                }
                ch.isHighSurrogate() && i + 1 < len && text[i + 1].isLowSurrogate() -> {
                    val codePoint =
                        SUPPLEMENTARY_BASE +
                            ((code - Char.MIN_HIGH_SURROGATE.code) shl SURROGATE_SHIFT) +
                            (text[i + 1].code - Char.MIN_LOW_SURROGATE.code)
                    out[o++] = (FOUR_BYTE_LEAD or (codePoint shr FOUR_BYTE_LEAD_SHIFT)).toByte()
                    out[o++] = continuation(codePoint shr SECOND_CONTINUATION_SHIFT)
                    out[o++] = continuation(codePoint shr CONTINUATION_SHIFT)
                    out[o++] = continuation(codePoint)
                    ++i
                }
                ch.isSurrogate() -> {
                    REPLACEMENT.copyInto(out, o)
                    o += THREE_BYTES
                }
                else -> {
                    out[o++] = (THREE_BYTE_LEAD or (code shr SECOND_CONTINUATION_SHIFT)).toByte()
                    out[o++] = continuation(code shr CONTINUATION_SHIFT)
                    out[o++] = continuation(code)
                }
            }
            i++
        }
        return out
    }

    private fun continuation(bits: Int): Byte = (CONTINUATION_MARKER or (bits and CONTINUATION_PAYLOAD_MASK)).toByte()
}
