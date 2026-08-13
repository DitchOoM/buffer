package com.ditchoom.buffer

/**
 * Common reference implementation of the [TextPolicy] UTF-8 decode contract: substituting decode
 * and validation. This is the single source of truth for the characters every platform must
 * produce — platform fast paths may override `readText` but MUST match this output exactly
 * (pinned by the cross-platform vectors in `Utf8TextDecoderTests`).
 *
 * Substitution follows the WHATWG/Unicode "maximal subpart" rule: each maximal prefix of a valid
 * UTF-8 sequence that cannot be completed is replaced by a single U+FFFD, and each ill-formed
 * byte outside such a prefix by one U+FFFD. This is exactly what a WHATWG `TextDecoder` produces
 * (expected outputs pinned against Node's TextDecoder), which is what makes the JS platform an
 * acceleration candidate for the lenient policy.
 *
 * The state machine is the WHATWG UTF-8 decode algorithm: lead-dependent continuation boundaries
 * (`E0`→A0..BF, `ED`→80..9F, `F0`→90..BF, `F4`→80..8F) reject overlong encodings, encoded
 * surrogates, and out-of-range code points at the byte where they become ill-formed, with no
 * post-hoc checks.
 */
internal object Utf8TextDecoder {
    private const val BYTE_MASK = 0xFF
    private const val ASCII_MAX = 0x7F
    private const val TWO_BYTE_LEAD_MIN = 0xC2
    private const val TWO_BYTE_LEAD_MAX = 0xDF
    private const val THREE_BYTE_LEAD_E0 = 0xE0
    private const val THREE_BYTE_LEAD_ED = 0xED
    private const val THREE_BYTE_LEAD_MAX = 0xEF
    private const val FOUR_BYTE_LEAD_F0 = 0xF0
    private const val FOUR_BYTE_LEAD_F4 = 0xF4
    private const val CONTINUATION_LOWER_DEFAULT = 0x80
    private const val CONTINUATION_UPPER_DEFAULT = 0xBF
    private const val E0_LOWER_BOUNDARY = 0xA0
    private const val ED_UPPER_BOUNDARY = 0x9F
    private const val F0_LOWER_BOUNDARY = 0x90
    private const val F4_UPPER_BOUNDARY = 0x8F
    private const val TWO_BYTE_PAYLOAD_MASK = 0x1F
    private const val THREE_BYTE_PAYLOAD_MASK = 0x0F
    private const val FOUR_BYTE_PAYLOAD_MASK = 0x07
    private const val REPLACEMENT_CHAR = '�'
    private const val TWO_BYTE_CONTINUATIONS = 1
    private const val THREE_BYTE_CONTINUATIONS = 2
    private const val FOUR_BYTE_CONTINUATIONS = 3

    /** Sentinel for [firstMalformedOffset]: the window is well-formed UTF-8. */
    const val WELL_FORMED = -1

    /**
     * Decodes `bytes[offset until offset + length]` as UTF-8, substituting U+FFFD per the
     * maximal subpart rule. Never fails; produces identical characters on every platform.
     */
    fun decodeSubstituting(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): String {
        val sb = StringBuilder(length)
        runMachine(bytes, offset, length, sb, stopOnMalformed = false)
        return sb.toString()
    }

    /**
     * Byte offset (relative to [offset]) of the **start** of the first ill-formed subsequence,
     * or [WELL_FORMED]. For a truncated-at-end sequence this is the lead byte's offset. This is
     * the offset decode failures report.
     */
    fun firstMalformedOffset(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int = runMachine(bytes, offset, length, null, stopOnMalformed = true)

    /**
     * WHATWG UTF-8 decode loop. With [sb] non-null, substitutes U+FFFD per maximal subpart and
     * decodes the whole window (return value is meaningless). With [sb] null, validates only:
     * returns the window-relative start offset of the first ill-formed subsequence, or
     * [WELL_FORMED].
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private fun runMachine(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        sb: StringBuilder?,
        stopOnMalformed: Boolean,
    ): Int {
        var i = offset
        val end = offset + length
        var needed = 0
        var codePoint = 0
        var lower = CONTINUATION_LOWER_DEFAULT
        var upper = CONTINUATION_UPPER_DEFAULT
        var sequenceStart = 0
        while (i < end) {
            val b = bytes[i].toInt() and BYTE_MASK
            if (needed == 0) {
                when {
                    b <= ASCII_MAX -> sb?.append(b.toChar())
                    b in TWO_BYTE_LEAD_MIN..TWO_BYTE_LEAD_MAX -> {
                        needed = TWO_BYTE_CONTINUATIONS
                        codePoint = b and TWO_BYTE_PAYLOAD_MASK
                        sequenceStart = i
                    }
                    b in THREE_BYTE_LEAD_E0..THREE_BYTE_LEAD_MAX -> {
                        needed = THREE_BYTE_CONTINUATIONS
                        codePoint = b and THREE_BYTE_PAYLOAD_MASK
                        if (b == THREE_BYTE_LEAD_E0) lower = E0_LOWER_BOUNDARY
                        if (b == THREE_BYTE_LEAD_ED) upper = ED_UPPER_BOUNDARY
                        sequenceStart = i
                    }
                    b in FOUR_BYTE_LEAD_F0..FOUR_BYTE_LEAD_F4 -> {
                        needed = FOUR_BYTE_CONTINUATIONS
                        codePoint = b and FOUR_BYTE_PAYLOAD_MASK
                        if (b == FOUR_BYTE_LEAD_F0) lower = F0_LOWER_BOUNDARY
                        if (b == FOUR_BYTE_LEAD_F4) upper = F4_UPPER_BOUNDARY
                        sequenceStart = i
                    }
                    else -> {
                        // Bare continuation, C0/C1, or F5..FF: one ill-formed byte, one U+FFFD.
                        if (stopOnMalformed) return i - offset
                        sb?.append(REPLACEMENT_CHAR)
                    }
                }
                i++
            } else if (b < lower || b > upper) {
                // The maximal subpart ends before this byte: substitute once, then reprocess
                // this byte as a fresh lead (do not consume it).
                if (stopOnMalformed) return sequenceStart - offset
                sb?.append(REPLACEMENT_CHAR)
                needed = 0
                lower = CONTINUATION_LOWER_DEFAULT
                upper = CONTINUATION_UPPER_DEFAULT
            } else {
                codePoint = (codePoint shl Utf8Wire.CONTINUATION_SHIFT) or (b and Utf8Wire.CONTINUATION_PAYLOAD_MASK)
                lower = CONTINUATION_LOWER_DEFAULT
                upper = CONTINUATION_UPPER_DEFAULT
                if (--needed == 0) sb?.appendCodePointCommon(codePoint)
                i++
            }
        }
        if (needed != 0) {
            // Truncated sequence at end of window: one maximal subpart.
            if (stopOnMalformed) return sequenceStart - offset
            sb?.append(REPLACEMENT_CHAR)
        }
        return WELL_FORMED
    }

    /** Appends [codePoint] as one char (BMP) or a surrogate pair (supplementary). */
    private fun StringBuilder.appendCodePointCommon(codePoint: Int) {
        if (codePoint < Utf8Wire.FOUR_BYTE_MIN) {
            append(codePoint.toChar())
        } else {
            val offsetCp = codePoint - Utf8Wire.FOUR_BYTE_MIN
            append((Utf8Wire.HIGH_SURROGATE_START + (offsetCp shr Utf8Wire.SURROGATE_SHIFT)).toChar())
            append((Utf8Wire.LOW_SURROGATE_START + (offsetCp and Utf8Wire.LOW_SURROGATE_MASK)).toChar())
        }
    }
}
