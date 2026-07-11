package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Locks the behaviour of the native UTF-8 write fast path (encodeUtf8ToNative), which replaces the
 * per-byte CharsetEncoder loop for native-backed buffers. Runs on the base JVM classpath
 * (DirectJvmBuffer / Unsafe) and, via jvmFfmTest, on the FFM buffers (FfmBuffer / FfmAutoBuffer).
 */
class WriteStringDirectUtf8Test {
    private fun roundTrip(text: String) {
        val bytes = text.encodeToByteArray()
        val buffer = BufferFactory.Default.allocate(bytes.size)
        buffer.writeString(text, Charset.UTF8)
        assertEquals(bytes.size, buffer.position(), "encoded byte count for \"$text\"")
        buffer.resetForRead()
        assertEquals(text, buffer.readString(bytes.size, Charset.UTF8))
    }

    @Test
    fun `ascii round-trips`() = roundTrip("hello, websocket!")

    @Test
    fun `two-byte round-trips`() = roundTrip("éüñ café")

    @Test
    fun `three-byte round-trips`() = roundTrip("世界 界世")

    @Test
    fun `four-byte surrogate pairs round-trip`() = roundTrip("😀🎉🚀 mixed 世 é a")

    @Test
    fun `deterministic (FFM on JVM 21) buffer round-trips multibyte`() {
        // The websocket's frame path uses BufferFactory.deterministic() — the buffer this fix targets.
        val text = "é世😀 deterministic"
        val bytes = text.encodeToByteArray()
        BufferFactory.deterministic().allocate(bytes.size).use { buffer ->
            buffer.writeString(text, Charset.UTF8)
            assertEquals(bytes.size, buffer.position())
            buffer.resetForRead()
            assertEquals(text, buffer.readString(bytes.size, Charset.UTF8))
        }
    }

    @Test
    fun `lone high surrogate throws`() {
        val buffer = BufferFactory.Default.allocate(8)
        assertFailsWith<java.nio.charset.MalformedInputException> {
            buffer.writeString("a\uD83Db", Charset.UTF8) // high surrogate not followed by a low one
        }
    }

    @Test
    fun `lone low surrogate throws`() {
        val buffer = BufferFactory.Default.allocate(8)
        assertFailsWith<java.nio.charset.MalformedInputException> {
            buffer.writeString("a\uDE00b", Charset.UTF8) // unpaired low surrogate
        }
    }

    @Test
    fun `trailing high surrogate at end of input throws`() {
        val buffer = BufferFactory.Default.allocate(8)
        assertFailsWith<java.nio.charset.MalformedInputException> {
            buffer.writeString("ab\uD83D", Charset.UTF8)
        }
    }
}
