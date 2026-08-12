// Surrogate code units (0xD800..0xDFFF) and UTF-8 byte counts are the subject under test;
// naming each literal would obscure the cases rather than clarify them.
@file:Suppress("MagicNumber")

package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [CharSequence.utf8Length] byte counts, including for ill-formed UTF-16 input.
 *
 * The contract for an unpaired surrogate is three bytes — the UTF-8 cost of the U+FFFD
 * replacement character — so the count matches byte-for-byte what every substituting
 * encoder writes (JS, WASM, `ByteArrayBuffer`, Apple), and over-states only for targets
 * that reject the write outright (JVM throws, Linux `NativeBuffer` no-ops), which is the
 * safe direction for a size used in length prefixes.
 *
 * These tests assert counts directly and deliberately do NOT round-trip through
 * `writeString`: on rejecting platforms such a round-trip would pass by throwing and
 * prove nothing about the count.
 */
class Utf8LengthTests {
    private fun assertUtf8Length(
        expected: Int,
        text: String,
        message: String,
    ) = assertEquals(expected, text.utf8Length(), message)

    @Test
    fun wellFormedText() {
        assertUtf8Length(0, "", "empty string")
        assertUtf8Length(1, "A", "one ASCII char")
        assertUtf8Length(2, "é", "é is two bytes")
        assertUtf8Length(3, "€", "€ is three bytes")
        assertUtf8Length(10, "aé€😀", "1+2+3+4 mixed widths")
    }

    @Test
    fun surrogatePairsCountFourBytes() {
        assertUtf8Length(4, "😀", "emoji pair")
        assertUtf8Length(4, "\uD800\uDC00", "minimum surrogate pair (U+10000)")
        assertUtf8Length(4, "\uDBFF\uDFFF", "maximum surrogate pair (U+10FFFF)")
    }

    @Test
    fun surrogateRangeBoundariesAreOrdinaryThreeByteChars() {
        assertUtf8Length(3, "\uD7FF", "last code point below the surrogate range")
        assertUtf8Length(3, "\uE000", "first code point above the surrogate range")
    }

    @Test
    fun unpairedSurrogatesCountAsReplacementChar() {
        assertUtf8Length(3, "\uD800", "lone high surrogate (range start)")
        assertUtf8Length(3, "\uDBFF", "lone high surrogate (range end)")
        assertUtf8Length(3, "\uDC00", "lone low surrogate (range start)")
        assertUtf8Length(3, "\uDFFF", "lone low surrogate (range end)")
        assertUtf8Length(6, "\uD800\uD800", "two consecutive lone high surrogates")
        assertUtf8Length(6, "\uDC00\uD800", "reversed pair is two lone surrogates")
    }

    @Test
    fun unpairedHighSurrogateMustNotSwallowTheNextChar() {
        // Regression: the pre-fix loop charged 4 for a high surrogate and skipped the
        // following char unconditionally, under-counting "\uD800€" as 4 instead of 6.
        assertUtf8Length(6, "\uD800€", "lone high then € (3 + 3)")
        assertUtf8Length(4, "\uD800A", "lone high then ASCII (3 + 1)")
        assertUtf8Length(7, "\uD800😀", "lone high then a valid pair (3 + 4)")
    }

    @Test
    fun unpairedHighSurrogateAtEndOfString() {
        assertUtf8Length(4, "A\uD800", "ASCII then lone high (1 + 3)")
        assertUtf8Length(5, "AB\uD800", "two ASCII then lone high (2 + 3)")
    }
}
