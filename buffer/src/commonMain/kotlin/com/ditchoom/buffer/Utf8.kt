package com.ditchoom.buffer

/**
 * Shared UTF-8 decode boundary constants used by the platform [StreamingStringDecoder] actuals
 * (appleMain, linuxMain) and the byte-packing idiom in the jvmCommon decoder.
 *
 * These are the byte-exact boundaries defined by the UTF-8 encoding (RFC 3629) and the UTF-16
 * surrogate-pair encoding (Unicode). They are centralized here so the per-platform decoders cannot
 * drift from one another. Every value is the literal it replaces — there is no behavior change.
 */
internal object Utf8 {
    /**
     * Mask isolating the low 8 bits of a value (one byte). Used when packing/unpacking pending
     * incomplete sequences into/out of a `Long`.
     */
    const val BYTE_MASK = 0xFF

    /** Number of bits in a byte; the shift step when packing successive bytes into a `Long`. */
    const val BITS_PER_BYTE = 8

    // --- Lead-byte / continuation-byte detection masks and markers (RFC 3629) ---

    /**
     * Any code point below this value is a single ASCII byte (`0xxxxxxx`); also the smallest
     * code point that legitimately requires a 2-byte sequence (overlong-encoding lower bound).
     */
    const val ASCII_LIMIT = 0x80

    /** Mask selecting the top two bits, used to test for a continuation byte (`10xxxxxx`). */
    const val CONTINUATION_MASK = 0xC0

    /** Marker value of a continuation byte after masking with [CONTINUATION_MASK] (`10xxxxxx`). */
    const val CONTINUATION_MARKER = 0x80

    /** Mask selecting the top three bits, used to detect a 2-byte lead (`110xxxxx`). */
    const val TWO_BYTE_LEAD_MASK = 0xE0

    /** Marker for a 2-byte lead byte after masking with [TWO_BYTE_LEAD_MASK] (`110xxxxx`). */
    const val TWO_BYTE_LEAD = 0xC0

    /** Mask selecting the top four bits, used to detect a 3-byte lead (`1110xxxx`). */
    const val THREE_BYTE_LEAD_MASK = 0xF0

    /** Marker for a 3-byte lead byte after masking with [THREE_BYTE_LEAD_MASK] (`1110xxxx`). */
    const val THREE_BYTE_LEAD = 0xE0

    /** Mask selecting the top five bits, used to detect a 4-byte lead (`11110xxx`). */
    const val FOUR_BYTE_LEAD_MASK = 0xF8

    /** Marker for a 4-byte lead byte after masking with [FOUR_BYTE_LEAD_MASK] (`11110xxx`). */
    const val FOUR_BYTE_LEAD = 0xF0

    // --- Payload masks: low bits carrying code-point data per byte ---

    /** Low 5 data bits of a 2-byte lead byte (`110xxxxx`). */
    const val TWO_BYTE_PAYLOAD_MASK = 0x1F

    /** Low 4 data bits of a 3-byte lead byte (`1110xxxx`). */
    const val THREE_BYTE_PAYLOAD_MASK = 0x0F

    /** Low 3 data bits of a 4-byte lead byte (`11110xxx`). */
    const val FOUR_BYTE_PAYLOAD_MASK = 0x07

    /** Low 6 data bits of a continuation byte (`10xxxxxx`). */
    const val CONTINUATION_PAYLOAD_MASK = 0x3F

    /** Bit shift contributed by each continuation byte (6 payload bits). */
    const val CONTINUATION_SHIFT = 6

    // --- Code-point range boundaries (overlong-encoding rejection & validation) ---

    /** Smallest code point that legitimately requires a 3-byte sequence. */
    const val THREE_BYTE_MIN = 0x800

    /** Smallest code point that legitimately requires a 4-byte sequence (and the supplementary-plane base). */
    const val FOUR_BYTE_MIN = 0x10000

    /** Largest valid Unicode code point. */
    const val MAX_CODE_POINT = 0x10FFFF

    // --- UTF-16 surrogate encoding (Unicode) ---

    /**
     * Start of the UTF-16 high-surrogate range; values in
     * [HIGH_SURROGATE_START]..[LOW_SURROGATE_END] are invalid scalars.
     */
    const val HIGH_SURROGATE_START = 0xD800

    /** Start of the UTF-16 low-surrogate range; emitted as the trailing surrogate for supplementary code points. */
    const val LOW_SURROGATE_START = 0xDC00

    /** End of the UTF-16 surrogate range. */
    const val LOW_SURROGATE_END = 0xDFFF

    /** Largest code point representable in a single UTF-16 code unit (Basic Multilingual Plane). */
    const val BMP_MAX = 0xFFFF

    /** Bit shift to extract the high-surrogate bits from a supplementary code point. */
    const val SURROGATE_SHIFT = 10

    /** Mask to extract the low-surrogate bits from a supplementary code point. */
    const val LOW_SURROGATE_MASK = 0x3FF
}
