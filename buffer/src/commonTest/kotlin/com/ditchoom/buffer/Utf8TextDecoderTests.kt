// UTF-8 byte patterns and code points are the subject under test; naming each literal
// would obscure the cases rather than clarify them.
@file:Suppress("MagicNumber")

package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the reference UTF-8 decode contract: maximal-subpart U+FFFD substitution and
 * first-malformed offsets. Expected outputs were pinned against a WHATWG `TextDecoder`
 * (Node 22), NOT derived from this implementation — the vectors are external truth.
 */
class Utf8TextDecoderTests {
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun decoded(data: ByteArray): String = Utf8TextDecoder.decodeSubstituting(data, 0, data.size)

    private fun offsetOf(data: ByteArray): Int = Utf8TextDecoder.firstMalformedOffset(data, 0, data.size)

    private val fffd = "\uFFFD"

    @Test
    fun wellFormedSequencesDecodeExactly() {
        assertEquals("", decoded(bytes()))
        assertEquals("A", decoded(bytes(0x41)))
        assertEquals("A€", decoded(bytes(0x41, 0xE2, 0x82, 0xAC)))
        assertEquals("é", decoded(bytes(0xC3, 0xA9)))
        assertEquals("😀", decoded(bytes(0xF0, 0x9F, 0x98, 0x80)))
        assertEquals("\uD7FF", decoded(bytes(0xED, 0x9F, 0xBF)), "highest code point before surrogate range")
        assertEquals("\uDBFF\uDFFF", decoded(bytes(0xF4, 0x8F, 0xBF, 0xBF)), "U+10FFFF")
        assertEquals(Utf8TextDecoder.WELL_FORMED, offsetOf(bytes(0x41, 0xE2, 0x82, 0xAC)))
    }

    @Test
    fun truncatedSequencesAreOneReplacementEach() {
        // A maximal subpart — however long — substitutes exactly ONE U+FFFD.
        assertEquals(fffd, decoded(bytes(0xC3)), "truncated 2-byte at end")
        assertEquals(fffd, decoded(bytes(0xE2, 0x82)), "truncated 3-byte at end")
        assertEquals(fffd, decoded(bytes(0xF0, 0x9F, 0x98)), "truncated 4-byte at end")
        assertEquals("A${fffd}B", decoded(bytes(0x41, 0xE2, 0x82, 0x42)), "truncated 3-byte mid-text resyncs")
        assertEquals("${fffd}A", decoded(bytes(0xC3, 0x41)), "lead then ASCII resyncs on the ASCII")
    }

    @Test
    fun illFormedBytesAreOneReplacementPerByte() {
        assertEquals(fffd, decoded(bytes(0x80)), "bare continuation")
        assertEquals("$fffd$fffd", decoded(bytes(0x80, 0x80)), "two bare continuations")
        assertEquals("$fffd$fffd", decoded(bytes(0xC0, 0xAF)), "overlong '/': C0 never valid, AF bare")
        assertEquals("$fffd$fffd$fffd", decoded(bytes(0xE0, 0x80, 0x80)), "overlong: E0 requires A0..BF")
        assertEquals("$fffd(", decoded(bytes(0xF0, 0x28)), "F0 requires 90..BF; '(' resyncs")
    }

    @Test
    fun surrogateAndOutOfRangeEncodingsFollowMaximalSubpart() {
        assertEquals("$fffd$fffd$fffd", decoded(bytes(0xED, 0xA0, 0x80)), "encoded U+D800: ED caps at 9F")
        assertEquals(
            "$fffd$fffd$fffd$fffd",
            decoded(bytes(0xF4, 0x90, 0x80, 0x80)),
            "U+110000: F4 caps at 8F",
        )
    }

    @Test
    fun firstMalformedOffsetReportsSubpartStart() {
        assertEquals(0, offsetOf(bytes(0x80)))
        assertEquals(0, offsetOf(bytes(0xC3)), "truncated at end reports the lead")
        assertEquals(1, offsetOf(bytes(0x41, 0xE2, 0x82, 0x42)), "mid-text reports the lead, not the resync byte")
        assertEquals(0, offsetOf(bytes(0xC3, 0x41)))
        assertEquals(0, offsetOf(bytes(0xED, 0xA0, 0x80)))
        assertEquals(2, offsetOf(bytes(0x41, 0x42, 0xF4, 0x90, 0x80, 0x80)))
        assertEquals(4, offsetOf(bytes(0xE2, 0x82, 0xAC, 0x41, 0x80)), "after a valid sequence")
    }

    @Test
    fun windowOffsetsAreRespected() {
        val data = bytes(0xFF, 0x41, 0xE2, 0x82, 0xAC, 0xFF)
        assertEquals("A€", Utf8TextDecoder.decodeSubstituting(data, 1, 4), "window excludes ill-formed neighbors")
        assertEquals(Utf8TextDecoder.WELL_FORMED, Utf8TextDecoder.firstMalformedOffset(data, 1, 4))
        assertEquals(3, Utf8TextDecoder.firstMalformedOffset(data, 2, 4), "offset is window-relative")
    }

    @Test
    fun roundTripWithReferenceEncoder() {
        // The two reference implementations must agree: encode(text) decoded == text
        // for well-formed text, including boundaries.
        for (text in listOf("", "A", "aé€😀", "퟿", "\uDBFF\uDFFF")) {
            val encoded = Utf8TextEncoder.encodeSubstituting(text)
            assertEquals(text, decoded(encoded), "round-trip must be identity for well-formed text")
            assertEquals(Utf8TextDecoder.WELL_FORMED, offsetOf(encoded))
        }
        // And an unpaired surrogate encodes to U+FFFD, which decodes as literal U+FFFD.
        // (Runtime-constructed: unpaired surrogates in string LITERALS are mangled to '?'
        // by clean Kotlin/JS builds \u2014 see TextEncodingTests.)
        assertEquals("A\uFFFD", decoded(Utf8TextEncoder.encodeSubstituting("A" + Char(0xD800))))
    }
}
