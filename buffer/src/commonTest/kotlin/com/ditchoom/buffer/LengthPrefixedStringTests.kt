package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LengthPrefixedStringTests {
    @Test
    fun emptyString() = roundTrip("")

    @Test
    fun asciiString() = roundTrip("hello")

    @Test
    fun multiByteUtf8() = roundTrip("\u00E9\u00E8\u00EA") // é è ê — 2-byte UTF-8 chars

    @Test
    fun threeByteUtf8() = roundTrip("\u4E16\u754C") // 世界 — 3-byte UTF-8 chars

    @Test
    fun fourByteUtf8() = roundTrip("\uD83D\uDE00") // 😀 — surrogate pair, 4-byte UTF-8

    @Test
    fun mixedContent() = roundTrip("hello \u4E16\u754C!")

    @Test
    fun returnsByteLengthNotCharLength() {
        val input = "\u00E9" // é — 2 bytes in UTF-8
        val bufferSize = UShort.SIZE_BYTES + input.utf8Length()
        val buffer = BufferFactory.Default.allocate(bufferSize)
        buffer.writeLengthPrefixedUtf8String(input)
        buffer.resetForRead()
        val (byteLength, decoded) = buffer.readLengthPrefixedUtf8String()
        assertEquals(2, byteLength)
        assertEquals(input, decoded)
    }

    @Test
    fun stringAtMaxUShortLength() {
        // A string whose UTF-8 encoding is exactly UShort.MAX_VALUE (65535) bytes
        val input = "a".repeat(65535)
        val bufferSize = UShort.SIZE_BYTES + 65535
        val buffer = BufferFactory.Default.allocate(bufferSize)
        buffer.writeLengthPrefixedUtf8String(input)
        buffer.resetForRead()
        val (byteLength, decoded) = buffer.readLengthPrefixedUtf8String()
        assertEquals(65535, byteLength)
        assertEquals(input, decoded)
    }

    @Test
    fun stringExceedingMaxUShortLengthThrows() {
        // A string whose UTF-8 encoding exceeds UShort.MAX_VALUE
        val input = "a".repeat(65536)
        val bufferSize = UShort.SIZE_BYTES + 65536
        val buffer = BufferFactory.Default.allocate(bufferSize)
        assertFailsWith<IllegalArgumentException> {
            buffer.writeLengthPrefixedUtf8String(input)
        }
    }

    private fun roundTrip(input: String) {
        val bufferSize = UShort.SIZE_BYTES + input.utf8Length()
        val buffer = BufferFactory.Default.allocate(bufferSize)
        buffer.writeLengthPrefixedUtf8String(input)
        buffer.resetForRead()
        val (_, decoded) = buffer.readLengthPrefixedUtf8String()
        assertEquals(input, decoded, "Failed round-trip for \"$input\"")
    }
}
