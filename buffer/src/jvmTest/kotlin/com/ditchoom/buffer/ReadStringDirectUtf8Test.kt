// UTF-8 bit patterns (0x80/0xC0/0xE0/0xF0 leads, 0x3F continuation, surrogate/overlong/ceiling
// boundaries) read clearer as literals than named constants in a decoder conformance test. All
// non-ASCII test text is written as \u escapes / code points so this source stays plain ASCII (a
// literal U+FFFF/U+10FFFF non-character or U+007F control would make git treat the file as binary).
@file:Suppress("MagicNumber")

package com.ditchoom.buffer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Locks the behaviour of the native UTF-8 read fast path (decodeUtf8FromNative), which replaces the
 * direct-ByteBuffer CharsetDecoder loop (decodeBufferLoop) on the FFM buffers. Via jvmFfmTest this
 * exercises FfmBuffer / FfmAutoBuffer (the native decode); on the base :jvmTest classpath
 * (DirectJvmBuffer) `readString` stays on the CharsetDecoder, so there these assertions simply
 * confirm the fallback still matches the reference.
 *
 * The decoder must be a byte-for-byte behavioural drop-in for the JDK `CharsetDecoder` in REPORT
 * mode, so most assertions are *parity* assertions against that reference: same String on success,
 * same throw on malformed input.
 */
class ReadStringDirectUtf8Test {
    private fun readViaBuffer(bytes: ByteArray): String {
        val buffer = BufferFactory.Default.allocate(maxOf(bytes.size, 1))
        buffer.writeBytes(bytes)
        buffer.resetForRead()
        return buffer.readString(bytes.size, Charset.UTF8)
    }

    /** The reference: JDK UTF-8 decoder in REPORT mode, exactly what readString used to delegate to. */
    private fun jdkDecode(bytes: ByteArray): Result<String> =
        runCatching {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }

    private fun assertParity(bytes: ByteArray) {
        val reference = jdkDecode(bytes)
        val ours = runCatching { readViaBuffer(bytes) }
        val label = bytes.joinToString(" ") { "0x%02X".format(it) }
        if (reference.isSuccess) {
            assertTrue(ours.isSuccess, "JDK decoded [$label] but ours threw ${ours.exceptionOrNull()}")
            assertEquals(reference.getOrNull(), ours.getOrNull(), "decoded value for [$label]")
        } else {
            val err = ours.exceptionOrNull()
            assertTrue(
                err is CharacterCodingException,
                "JDK rejected [$label] but ours returned ${ours.getOrNull()?.let { "\"$it\"" } ?: err}",
            )
        }
    }

    @Test
    fun `valid strings round-trip and match the JDK`() {
        listOf(
            "",
            "hello, websocket!",
            "héllo café", // 2-byte scalars
            "世界 界世", // 3-byte CJK
            cp(0x1F600) + cp(0x1F389) + " mixed 世 é a", // 4-byte emoji + mix
            "\u007f", // highest 1-byte scalar
            "߿", // highest 2-byte scalar
            "\u0800\uffff", // 3-byte range ends (U+FFFF is a valid, non-surrogate scalar)
            cp(0x10000), // lowest 4-byte scalar
            cp(0x10FFFF), // highest scalar
            "a".repeat(5000), // long ASCII run (the cat-13 shape)
        ).forEach { assertParity(it.encodeToByteArray()) }
    }

    @Test
    fun `malformed sequences throw exactly where the JDK does`() {
        val malformed =
            listOf(
                byteArrayOf(0x80.toByte()), // lone continuation
                byteArrayOf(0xBF.toByte()),
                byteArrayOf(0xC0.toByte(), 0x80.toByte()), // overlong 2-byte
                byteArrayOf(0xC1.toByte(), 0xBF.toByte()),
                byteArrayOf(0xC3.toByte(), 0x28), // bad continuation
                byteArrayOf(0xC3.toByte()), // truncated 2-byte
                byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0x80.toByte()), // overlong 3-byte
                byteArrayOf(0xE0.toByte(), 0x9F.toByte(), 0xBF.toByte()),
                byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), // U+D800 surrogate
                byteArrayOf(0xED.toByte(), 0xBF.toByte(), 0xBF.toByte()), // U+DFFF surrogate
                byteArrayOf(0xE4.toByte(), 0xB8.toByte()), // truncated 3-byte
                byteArrayOf(0xE4.toByte()),
                byteArrayOf(0xF0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()), // overlong 4-byte
                byteArrayOf(0xF0.toByte(), 0x8F.toByte(), 0xBF.toByte(), 0xBF.toByte()),
                byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()), // U+110000
                byteArrayOf(0xF5.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()), // invalid lead
                byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte()), // truncated 4-byte
                byteArrayOf(0xF8.toByte()), // invalid lead
                byteArrayOf(0xFF.toByte()),
                byteArrayOf(0x61, 0xFF.toByte()), // valid then invalid
                byteArrayOf(0x61, 0xE4.toByte(), 0xB8.toByte(), 0xAD.toByte(), 0xFF.toByte()), // valid... then bad
            )
        malformed.forEach { assertParity(it) }
    }

    @Test
    fun `exhaustive two-byte lead x continuation parity`() {
        // Every (lead 0xC0..0xDF, second 0x00..0xFF) pair: our accept/reject must equal the JDK's.
        for (lead in 0xC0..0xDF) {
            for (second in 0x00..0xFF) {
                assertParity(byteArrayOf(lead.toByte(), second.toByte()))
            }
        }
    }

    @Test
    fun `every scalar in the BMP and a sweep of astral planes round-trips`() {
        val sb = StringBuilder()
        for (scalar in 0..0xFFFF) {
            if (scalar in 0xD800..0xDFFF) continue // unpaired surrogates aren't valid scalars
            sb.appendCodePoint(scalar)
        }
        var scalar = 0x10000
        while (scalar <= 0x10FFFF) {
            sb.appendCodePoint(scalar)
            scalar += 0x40 // sample the astral planes; a full sweep is 1M+ and adds no new code path
        }
        assertParity(sb.toString().encodeToByteArray())
    }

    @Test
    fun `malformed read leaves position unchanged for a retry`() {
        val buffer = BufferFactory.Default.allocate(4)
        buffer.writeBytes(byteArrayOf(0xC3.toByte(), 0x28, 0x41, 0x42)) // bad pair, then "AB"
        buffer.resetForRead()
        assertFailsWith<CharacterCodingException> { buffer.readString(2, Charset.UTF8) }
        // A throw must not consume input: re-reading the same bytes as a longer window still sees them.
        assertEquals(0, buffer.position(), "position must be unchanged after a malformed read")
    }

    @Test
    fun `readString past the buffer throws instead of an out-of-bounds native read`() {
        // The FFM fast path reads raw native memory with no per-byte bounds check, so a length that
        // runs past the segment used to SIGSEGV (caught by the MQTT control-packet fuzzers on a
        // malformed UTF-8 length prefix). It must instead throw, exactly as the CharsetDecoder path
        // does when asked to read past capacity, and leave the read position untouched for a retry.
        val buffer = BufferFactory.Default.allocate(4)
        buffer.writeBytes(byteArrayOf(0x41, 0x42, 0x43, 0x44)) // "ABCD"
        buffer.resetForRead()
        assertFailsWith<IllegalArgumentException> { buffer.readString(64, Charset.UTF8) }
        assertEquals(0, buffer.position(), "a rejected over-read must not advance position")
    }

    @Test
    fun `non-UTF8 charset falls back to the decoder path`() {
        val bytes = byteArrayOf(0xE9.toByte(), 0x20) // 0xE9 = U+00E9 in Latin-1
        val buffer = BufferFactory.Default.allocate(bytes.size)
        buffer.writeBytes(bytes)
        buffer.resetForRead()
        assertEquals("é ", buffer.readString(bytes.size, Charset.ISOLatin1))
    }

    @Test
    fun `partial reads advance position across multibyte boundaries`() {
        val text = "aé世" + cp(0x1F600) + "z" // a, U+00E9 (2B), U+4E16 (3B), emoji (4B), z
        val bytes = text.encodeToByteArray()
        val buffer = BufferFactory.Default.allocate(bytes.size)
        buffer.writeBytes(bytes)
        buffer.resetForRead()
        assertEquals("a", buffer.readString(1, Charset.UTF8))
        assertEquals("é", buffer.readString(2, Charset.UTF8))
        assertEquals("世", buffer.readString(3, Charset.UTF8))
        assertEquals(cp(0x1F600) + "z", buffer.readString(bytes.size - 6, Charset.UTF8))
        if (buffer.remaining() != 0) fail("expected buffer fully consumed")
    }

    private companion object {
        /** A String holding the single Unicode scalar [scalar] (a surrogate pair for astral ones). */
        private fun cp(scalar: Int): String = String(Character.toChars(scalar))
    }
}
