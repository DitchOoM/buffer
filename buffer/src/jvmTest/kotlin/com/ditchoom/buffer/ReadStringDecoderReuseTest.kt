package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Guards the thread-local decoder + reused CharBuffer path added for effort-vs-win optimization #2
 * ([DefaultDecoder] / decodeReusing in CharsetDecoderHelper). The reuse must not leak state between
 * calls and must preserve the CodingErrorAction.REPORT behaviour the previous per-call decoder had.
 */
class ReadStringDecoderReuseTest {
    private fun bufferOf(bytes: ByteArray): PlatformBuffer {
        val buffer = BufferFactory.Default.allocate(bytes.size)
        buffer.writeBytes(bytes)
        buffer.resetForRead()
        return buffer
    }

    @Test
    fun `malformed UTF-8 still throws instead of substituting`() {
        // 0xC3 starts a 2-byte sequence; 0x28 '(' is not a valid continuation byte.
        val buffer = bufferOf(byteArrayOf(0xC3.toByte(), 0x28))
        assertFailsWith<java.nio.charset.CharacterCodingException> {
            buffer.readString(2, Charset.UTF8)
        }
    }

    @Test
    fun `reused decoder is reset between calls after a malformed read`() {
        val malformed = bufferOf(byteArrayOf(0xC3.toByte(), 0x28))
        assertFailsWith<java.nio.charset.CharacterCodingException> {
            malformed.readString(2, Charset.UTF8)
        }
        // A subsequent well-formed read on the same thread must not inherit stale decoder state.
        val ok = bufferOf("hello".encodeToByteArray())
        assertEquals("hello", ok.readString(5, Charset.UTF8))
    }

    @Test
    fun `back-to-back reads of shrinking then growing payloads are correct`() {
        // Exercises the reused CharBuffer at different sizes: a large read grows it, a small read
        // reuses the grown buffer (must clear, not read stale tail), then a larger read again.
        val big = "A".repeat(5000)
        val small = "hi"
        val bigger = "Z".repeat(9000)
        assertEquals(big, bufferOf(big.encodeToByteArray()).readString(5000, Charset.UTF8))
        assertEquals(small, bufferOf(small.encodeToByteArray()).readString(2, Charset.UTF8))
        assertEquals(bigger, bufferOf(bigger.encodeToByteArray()).readString(9000, Charset.UTF8))
    }

    @Test
    fun `multibyte and emoji round-trip through the reused CharBuffer`() {
        val text = "é世ü界😀"
        val bytes = text.encodeToByteArray()
        assertEquals(text, bufferOf(bytes).readString(bytes.size, Charset.UTF8))
    }
}
