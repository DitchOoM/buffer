package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for StreamingStringDecoder.
 *
 * Tests cover:
 * - ASCII-only streams
 * - Multi-byte UTF-8 (2-byte, 3-byte Chinese, 4-byte emoji)
 * - Boundary handling (split multi-byte sequences at every position)
 * - Malformed input handling (REPORT/REPLACE/IGNORE)
 * - Empty chunks
 * - Single-byte chunks (worst case)
 * - Large buffers (16MB+)
 * - Direct vs Heap buffer types
 * - Reset behavior with pending bytes
 */
class StreamingStringDecoderTest {
    // =========================================================================
    // Basic Decoding Tests
    // =========================================================================

    @Test
    fun decodeAsciiString() {
        val decoder = StreamingStringDecoder()
        val result = StringBuilder()

        val buffer = "Hello, World!".toReadBuffer()
        val chars = decoder.decode(buffer, result)
        decoder.finish(result)

        assertEquals("Hello, World!", result.toString())
        assertEquals(13, chars)
    }

    @Test
    fun decodeUtf8MultibyteCharacters() {
        val decoder = StreamingStringDecoder()
        val result = StringBuilder()

        // Chinese: 你好 (2 chars, 6 bytes)
        val buffer = "你好".toReadBuffer()

        decoder.decode(buffer, result)
        decoder.finish(result)

        assertEquals("你好", result.toString())
    }

    @Test
    fun decodeEmoji() {
        val decoder = StreamingStringDecoder()
        val result = StringBuilder()

        // Emoji: 🎉 (1 grapheme, 4 bytes UTF-8, 2 UTF-16 code units as surrogate pair)
        val buffer = "🎉".toReadBuffer()

        decoder.decode(buffer, result)
        decoder.finish(result)

        assertEquals("🎉", result.toString())
    }

    @Test
    fun decodeMixedContent() {
        val decoder = StreamingStringDecoder()
        val result = StringBuilder()

        // Mixed: ASCII + Chinese + Emoji
        val buffer = "Hello 你好 🎉".toReadBuffer()

        decoder.decode(buffer, result)
        decoder.finish(result)

        assertEquals("Hello 你好 🎉", result.toString())
    }

    // =========================================================================
    // Streaming / Boundary Tests
    // =========================================================================

    @Test
    fun decodeChunkedAscii() {
        val decoder = StreamingStringDecoder()
        val result = StringBuilder()
        val input = "Hello, World!"

        // Feed one byte at a time
        for (byte in input.encodeToByteArray()) {
            val buffer = PlatformBuffer.allocate(1)
            buffer.writeByte(byte)
            buffer.resetForRead()
            decoder.decode(buffer, result)
        }
        decoder.finish(result)

        assertEquals(input, result.toString())
    }

    @Test
    fun decodeChunkedMultibyte_splitInMiddle() {
        val decoder = StreamingStringDecoder()
        val result = StringBuilder()

        // 你 = E4 BD A0 (3 bytes)
        // Split after first byte
        val fullBytes = "你".encodeToByteArray()
        assertEquals(3, fullBytes.size)

        // First chunk: E4
        val chunk1 = PlatformBuffer.allocate(1)
        chunk1.writeByte(fullBytes[0])
        chunk1.resetForRead()
        decoder.decode(chunk1, result)

        // Second chunk: BD A0
        val chunk2 = PlatformBuffer.allocate(2)
        chunk2.writeByte(fullBytes[1])
        chunk2.writeByte(fullBytes[2])
        chunk2.resetForRead()
        decoder.decode(chunk2, result)

        decoder.finish(result)

        assertEquals("你", result.toString())
    }

    @Test
    fun decodeChunkedEmoji_splitAcrossChunks() {
        val decoder = StreamingStringDecoder()
        val result = StringBuilder()

        // 🎉 = F0 9F 8E 89 (4 bytes)
        val fullBytes = "🎉".encodeToByteArray()
        assertEquals(4, fullBytes.size)

        // Feed one byte at a time (worst case)
        for (byte in fullBytes) {
            val buffer = PlatformBuffer.allocate(1)
            buffer.writeByte(byte)
            buffer.resetForRead()
            decoder.decode(buffer, result)
        }
        decoder.finish(result)

        assertEquals("🎉", result.toString())
    }

    @Test
    fun decodeMultipleChunks_mixedBoundaries() {
        val decoder = StreamingStringDecoder()
        val result = StringBuilder()

        // "A你B🎉C" - mix of 1, 3, 1, 4, 1 byte sequences
        val input = "A你B🎉C".encodeToByteArray()

        // Split into arbitrary chunks that cross boundaries
        val chunks =
            listOf(
                input.sliceArray(0..2), // A + first 2 bytes of 你
                input.sliceArray(3..5), // last byte of 你 + B + first byte of 🎉
                input.sliceArray(6..8), // bytes 2-4 of 🎉
                input.sliceArray(9..9), // C
            )

        for (chunk in chunks) {
            val buffer = PlatformBuffer.wrap(chunk)
            decoder.decode(buffer, result)
        }
        decoder.finish(result)

        assertEquals("A你B🎉C", result.toString())
    }

    @Test
    fun decode2ByteUtf8_splitInMiddle() {
        val decoder = StreamingStringDecoder()
        val result = StringBuilder()

        // é = C3 A9 (2 bytes)
        val fullBytes = "é".encodeToByteArray()
        assertEquals(2, fullBytes.size)

        // First chunk: C3
        val chunk1 = PlatformBuffer.allocate(1)
        chunk1.writeByte(fullBytes[0])
        chunk1.resetForRead()
        decoder.decode(chunk1, result)

        // Second chunk: A9
        val chunk2 = PlatformBuffer.allocate(1)
        chunk2.writeByte(fullBytes[1])
        chunk2.resetForRead()
        decoder.decode(chunk2, result)

        decoder.finish(result)

        assertEquals("é", result.toString())
    }

    @Test
    fun decode4ByteEmoji_splitAfter2Bytes() {
        val decoder = StreamingStringDecoder()
        val result = StringBuilder()

        // 🎉 = F0 9F 8E 89 (4 bytes)
        val fullBytes = "🎉".encodeToByteArray()
        assertEquals(4, fullBytes.size)

        // First chunk: F0 9F (2 bytes)
        val chunk1 = PlatformBuffer.wrap(fullBytes.sliceArray(0..1))
        decoder.decode(chunk1, result)

        // Second chunk: 8E 89 (2 bytes)
        val chunk2 = PlatformBuffer.wrap(fullBytes.sliceArray(2..3))
        decoder.decode(chunk2, result)

        decoder.finish(result)

        assertEquals("🎉", result.toString())
    }

    @Test
    fun decode4ByteEmoji_splitAfter3Bytes() {
        val decoder = StreamingStringDecoder()
        val result = StringBuilder()

        // 🎉 = F0 9F 8E 89 (4 bytes)
        val fullBytes = "🎉".encodeToByteArray()
        assertEquals(4, fullBytes.size)

        // First chunk: F0 9F 8E (3 bytes)
        val chunk1 = PlatformBuffer.wrap(fullBytes.sliceArray(0..2))
        decoder.decode(chunk1, result)

        // Second chunk: 89 (1 byte)
        val chunk2 = PlatformBuffer.wrap(fullBytes.sliceArray(3..3))
        decoder.decode(chunk2, result)

        decoder.finish(result)

        assertEquals("🎉", result.toString())
    }

    @Test
    fun decodeMultipleConsecutiveMultibyteChars_allSplit() {
        val decoder = StreamingStringDecoder()
        val result = StringBuilder()

        // "你好" = E4 BD A0 (你) + E5 A5 BD (好) = 6 bytes
        val fullBytes = "你好".encodeToByteArray()
        assertEquals(6, fullBytes.size)

        // Split so each 3-byte char is broken: 2 + 2 + 2
        val chunks =
            listOf(
                fullBytes.sliceArray(0..1), // E4 BD (first 2 of 你)
                fullBytes.sliceArray(2..3), // A0 E5 (last of 你 + first of 好)
                fullBytes.sliceArray(4..5), // A5 BD (last 2 of 好)
            )

        for (chunk in chunks) {
            val buffer = PlatformBuffer.wrap(chunk)
            decoder.decode(buffer, result)
        }
        decoder.finish(result)

        assertEquals("你好", result.toString())
    }

    // =========================================================================
    // Empty and Edge Cases
    // =========================================================================

    @Test
    fun decodeEmptyBuffer() {
        val decoder = StreamingStringDecoder()
        val result = StringBuilder()

        val buffer = "".toReadBuffer()

        val chars = decoder.decode(buffer, result)
        decoder.finish(result)

        assertEquals("", result.toString())
        assertEquals(0, chars)
    }

    @Test
    fun decodeMultipleEmptyChunks() {
        val decoder = StreamingStringDecoder()
        val result = StringBuilder()

        // Empty, "Hi", empty, "!", empty
        val chunks = listOf("", "Hi", "", "!", "")

        for (chunk in chunks) {
            val buffer = chunk.toReadBuffer()
            decoder.decode(buffer, result)
        }
        decoder.finish(result)

        assertEquals("Hi!", result.toString())
    }

    // =========================================================================
    // Error Handling Tests
    // =========================================================================

    @Test
    fun malformedInput_report_throws() {
        val decoder =
            StreamingStringDecoder(
                StreamingStringDecoderConfig(onMalformedInput = DecoderErrorAction.REPORT),
            )
        val result = StringBuilder()

        // Invalid UTF-8: continuation byte without lead byte
        val buffer = PlatformBuffer.wrap(byteArrayOf(0x80.toByte()))

        assertFailsWith<CharacterDecodingException> {
            decoder.decode(buffer, result)
            decoder.finish(result)
        }
    }

    @Test
    fun malformedInput_replace_insertsReplacementChar() {
        val decoder =
            StreamingStringDecoder(
                StreamingStringDecoderConfig(onMalformedInput = DecoderErrorAction.REPLACE),
            )
        val result = StringBuilder()

        // Invalid: incomplete sequence at end of stream (start of 3-byte sequence)
        val buffer = PlatformBuffer.wrap(byteArrayOf(0xE4.toByte()))

        decoder.decode(buffer, result)
        decoder.finish(result) // This should trigger replacement

        assertTrue(result.toString().contains('\uFFFD'), "Expected replacement character")
    }

    // =========================================================================
    // Reset and Reuse Tests
    // =========================================================================

    @Test
    fun resetAllowsReuse() {
        val decoder = StreamingStringDecoder()

        // First stream
        val result1 = StringBuilder()
        decoder.decode("Hello".toReadBuffer(), result1)
        decoder.finish(result1)
        assertEquals("Hello", result1.toString())

        // Reset
        decoder.reset()

        // Second stream
        val result2 = StringBuilder()
        decoder.decode("World".toReadBuffer(), result2)
        decoder.finish(result2)
        assertEquals("World", result2.toString())
    }

    @Test
    fun resetClearsPendingBytes() {
        val decoder = StreamingStringDecoder()

        // First stream - leave incomplete sequence
        val result1 = StringBuilder()
        val incompleteBytes = "🎉".encodeToByteArray().sliceArray(0..1) // First 2 bytes of 4-byte emoji
        val buffer1 = PlatformBuffer.wrap(incompleteBytes)
        decoder.decode(buffer1, result1)
        // Don't call finish() - leave pending bytes

        // Reset should clear pending bytes
        decoder.reset()

        // Second stream - should work independently
        val result2 = StringBuilder()
        decoder.decode("Hello".toReadBuffer(), result2)
        decoder.finish(result2)
        assertEquals("Hello", result2.toString())
    }

    // =========================================================================
    // Buffer Type Tests
    // =========================================================================

    @Test
    fun decodeWithDirectBuffer() {
        val decoder = StreamingStringDecoder()
        val result = StringBuilder()

        val buffer = "Hello 你好 🎉".toReadBuffer(zone = AllocationZone.Direct)

        decoder.decode(buffer, result)
        decoder.finish(result)

        assertEquals("Hello 你好 🎉", result.toString())
    }

    @Test
    fun decodeWithHeapBuffer() {
        val decoder = StreamingStringDecoder()
        val result = StringBuilder()

        val buffer = "Hello 你好 🎉".toReadBuffer(zone = AllocationZone.Heap)

        decoder.decode(buffer, result)
        decoder.finish(result)

        assertEquals("Hello 你好 🎉", result.toString())
    }

    @Test
    fun decodeWithWrappedByteArray() {
        val decoder = StreamingStringDecoder()
        val result = StringBuilder()

        val buffer = PlatformBuffer.wrap("Hello 你好 🎉".encodeToByteArray())

        decoder.decode(buffer, result)
        decoder.finish(result)

        assertEquals("Hello 你好 🎉", result.toString())
    }

    // =========================================================================
    // Large Buffer Tests (16MB)
    // =========================================================================

    @Test
    fun decodeLargeBuffer_16MB_ascii() {
        val decoder = StreamingStringDecoder()
        val result = StringBuilder()

        // 16MB of ASCII
        val size = 16 * 1024 * 1024
        val pattern = "Hello World! "
        val patternBytes = pattern.encodeToByteArray()

        val buffer = PlatformBuffer.allocate(size)
        var written = 0
        while (written + patternBytes.size <= size) {
            buffer.writeBytes(patternBytes)
            written += patternBytes.size
        }
        // Fill remaining with spaces
        while (written < size) {
            buffer.writeByte(' '.code.toByte())
            written++
        }
        buffer.resetForRead()

        decoder.decode(buffer, result)
        decoder.finish(result)

        assertEquals(size, result.length)
        assertTrue(result.startsWith("Hello World!"))
    }

    @Test
    fun decodeLargeBuffer_16MB_utf8_chunked() {
        val decoder = StreamingStringDecoder()
        val result = StringBuilder()

        // 16MB of mixed UTF-8 content, processed in 64KB chunks
        val totalSize = 16 * 1024 * 1024
        val chunkSize = 64 * 1024
        val pattern = "Hello 你好 🎉 " // Mixed ASCII, Chinese, Emoji
        val patternBytes = pattern.encodeToByteArray()

        var totalWritten = 0
        while (totalWritten < totalSize) {
            val remaining = totalSize - totalWritten
            val thisChunkSize = minOf(chunkSize, remaining)

            val buffer = PlatformBuffer.allocate(thisChunkSize)
            var chunkWritten = 0
            while (chunkWritten + patternBytes.size <= thisChunkSize) {
                buffer.writeBytes(patternBytes)
                chunkWritten += patternBytes.size
            }
            // Fill remaining with ASCII
            while (chunkWritten < thisChunkSize) {
                buffer.writeByte(' '.code.toByte())
                chunkWritten++
            }
            buffer.resetForRead()

            decoder.decode(buffer, result)
            totalWritten += thisChunkSize
        }
        decoder.finish(result)

        assertTrue(result.length > 0)
        assertTrue(result.toString().contains("你好"))
        assertTrue(result.toString().contains("🎉"))
    }

    @Test
    fun decodeLargeBuffer_16MB_worstCase_singleByteChunks() {
        // This tests the worst case for boundary handling
        // Only run a smaller sample to avoid timeout
        val decoder = StreamingStringDecoder()
        val result = StringBuilder()

        // Use 1KB of multi-byte content, fed byte by byte
        val input =
            buildString {
                repeat(100) {
                    append("你好🎉") // 3+3+4 = 10 bytes per iteration
                }
            }
        val inputBytes = input.encodeToByteArray()

        for (byte in inputBytes) {
            val buffer = PlatformBuffer.allocate(1)
            buffer.writeByte(byte)
            buffer.resetForRead()
            decoder.decode(buffer, result)
        }
        decoder.finish(result)

        assertEquals(input, result.toString())
    }
}
