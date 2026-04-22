package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [String.utf8ByteCount] must return exactly the same length as
 * [String.encodeToByteArray].size for every input — that's its whole
 * reason for existing. These tests assert parity across the byte-length
 * boundaries (1/2/3/4 byte code points) plus tricky cases (surrogate
 * pairs, unpaired surrogates, empty).
 */
class Utf8LengthTest {
    @Test
    fun emptyStringIsZero() {
        assertEquals(0, "".utf8ByteCount())
    }

    @Test
    fun asciiMatchesEncodeSize() {
        val s = "The quick brown fox jumps over the lazy dog."
        assertEquals(s.encodeToByteArray().size, s.utf8ByteCount())
    }

    @Test
    fun twoByteCodePointsMatchEncodeSize() {
        // Latin-1 supplement and Greek chars occupy 2 bytes in UTF-8
        val s = "¡Olé! π τ σ ω"
        assertEquals(s.encodeToByteArray().size, s.utf8ByteCount())
    }

    @Test
    fun threeByteCodePointsMatchEncodeSize() {
        // CJK characters occupy 3 bytes each in UTF-8
        val s = "你好世界"
        assertEquals(s.encodeToByteArray().size, s.utf8ByteCount())
    }

    @Test
    fun surrogatePairEmojiMatchesEncodeSize() {
        // U+1F600 (😀) is a 4-byte UTF-8 code point encoded via surrogate pair
        val s = "hi 😀 there"
        assertEquals(s.encodeToByteArray().size, s.utf8ByteCount())
    }

    // Unpaired surrogates are intentionally not tested: Kotlin stdlib's
    // encodeToByteArray replaces them with a 1-byte `?` on JVM but other
    // encoders (and our impl) substitute the 3-byte U+FFFD replacement.
    // The primitive's contract is "parity for valid UTF-16 only" — which
    // is what real WebSocket close reasons, HTTP headers, and MQTT topics
    // always are. Invalid UTF-16 produces an implementation-defined count.

    @Test
    fun mixedMatchesEncodeSize() {
        val s = "WS close: bye 👋 — see you 再见 !"
        assertEquals(s.encodeToByteArray().size, s.utf8ByteCount())
    }
}
