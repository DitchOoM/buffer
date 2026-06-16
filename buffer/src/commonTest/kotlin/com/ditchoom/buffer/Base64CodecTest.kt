package com.ditchoom.buffer

import com.ditchoom.buffer.pool.BufferPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the buffer-to-buffer Base64 encode/decode primitives (encodeBase64Into / decodeBase64Into)
 * on [ReadBuffer]. Covers RFC 4648 vectors, standard vs URL-safe, padded vs unpadded, round-trips,
 * position semantics, error handling, and pool-wrapper transparency.
 */
class Base64CodecTest {
    private fun bytesBuffer(values: List<Int>): PlatformBuffer {
        val b = BufferFactory.Default.allocate(maxOf(values.size, 1))
        for (v in values) b.writeByte(v.toByte())
        b.resetForRead()
        return b
    }

    private fun textBuffer(text: String): PlatformBuffer {
        val b = BufferFactory.Default.allocate(maxOf(text.length, 1))
        b.writeString(text)
        b.resetForRead()
        return b
    }

    private fun encodeToString(
        text: String,
        urlSafe: Boolean = false,
        padded: Boolean = true,
    ): String {
        val src = textBuffer(text)
        val n = base64EncodedLength(src.remaining(), padded)
        val dest = BufferFactory.Default.allocate(maxOf(n, 1))
        src.encodeBase64Into(dest, urlSafe = urlSafe, padded = padded)
        dest.resetForRead()
        return dest.readString(n)
    }

    // region RFC 4648 §10 known vectors

    @Test
    fun encodesRfc4648Vectors() {
        assertEquals("", encodeToString(""))
        assertEquals("Zg==", encodeToString("f"))
        assertEquals("Zm8=", encodeToString("fo"))
        assertEquals("Zm9v", encodeToString("foo"))
        assertEquals("Zm9vYg==", encodeToString("foob"))
        assertEquals("Zm9vYmE=", encodeToString("fooba"))
        assertEquals("Zm9vYmFy", encodeToString("foobar"))
    }

    @Test
    fun encodesUnpadded() {
        assertEquals("Zg", encodeToString("f", padded = false))
        assertEquals("Zm8", encodeToString("fo", padded = false))
        assertEquals("Zm9v", encodeToString("foo", padded = false))
    }

    @Test
    fun encodesUrlSafeAlphabet() {
        // 0xFB 0xFF 0xBF -> standard "+/+/"-ish; pick bytes that exercise '+' and '/'.
        val src = bytesBuffer(listOf(0xFB, 0xFF, 0xBF))
        val std = BufferFactory.Default.allocate(4)
        src.encodeBase64Into(std)
        std.resetForRead()
        src.resetForRead()
        val url = BufferFactory.Default.allocate(4)
        src.encodeBase64Into(url, urlSafe = true)
        url.resetForRead()

        val stdStr = std.readString(4)
        val urlStr = url.readString(4)
        assertTrue(stdStr.contains('+') || stdStr.contains('/'), "expected +// in $stdStr")
        assertEquals(stdStr.replace('+', '-').replace('/', '_'), urlStr)
    }

    // endregion

    // region decode

    @Test
    fun decodesRfc4648Vectors() {
        assertEquals("f", decodeToString("Zg=="))
        assertEquals("fo", decodeToString("Zm8="))
        assertEquals("foo", decodeToString("Zm9v"))
        assertEquals("foobar", decodeToString("Zm9vYmFy"))
    }

    @Test
    fun decodesUnpaddedAndUrlSafe() {
        assertEquals("foob", decodeToString("Zm9vYg"))
        // URL-safe input with the chars that differ from standard.
        val urlInput = encodeToString2(listOf(0xFB, 0xFF, 0xBF), urlSafe = true)
        val decoded = decodeBytes(urlInput)
        assertEquals(listOf(0xFB, 0xFF, 0xBF), decoded)
    }

    @Test
    fun invalidCharDecodeThrows() {
        val src = textBuffer("Zm9v*===")
        val dest = BufferFactory.Default.allocate(8)
        assertFailsWith<IllegalArgumentException> { src.decodeBase64Into(dest) }
    }

    private fun decodeToString(b64: String): String {
        val src = textBuffer(b64)
        val dest = BufferFactory.Default.allocate(base64DecodedMaxLength(b64.length).coerceAtLeast(1))
        src.decodeBase64Into(dest)
        val produced = dest.position()
        dest.resetForRead()
        return dest.readString(produced)
    }

    private fun decodeBytes(b64: String): List<Int> {
        val src = textBuffer(b64)
        val dest = BufferFactory.Default.allocate(base64DecodedMaxLength(b64.length).coerceAtLeast(1))
        src.decodeBase64Into(dest)
        val produced = dest.position()
        dest.resetForRead()
        return (0 until produced).map { dest.readByte().toInt() and 0xFF }
    }

    private fun encodeToString2(
        values: List<Int>,
        urlSafe: Boolean,
    ): String {
        val src = bytesBuffer(values)
        val n = base64EncodedLength(src.remaining())
        val dest = BufferFactory.Default.allocate(n)
        src.encodeBase64Into(dest, urlSafe = urlSafe)
        dest.resetForRead()
        return dest.readString(n)
    }

    // endregion

    // region round-trip

    @Test
    fun roundTripsAllByteValues() {
        val src = bytesBuffer((0..255).toList())
        val n = base64EncodedLength(256)
        val b64 = BufferFactory.Default.allocate(n)
        src.encodeBase64Into(b64)
        b64.resetForRead()

        val decoded = BufferFactory.Default.allocate(256)
        b64.decodeBase64Into(decoded)
        decoded.resetForRead()
        src.resetForRead()
        assertTrue(src.contentEquals(decoded))
    }

    @Test
    fun roundTripsUnpaddedUrlSafeWithManagedDestination() {
        // Exercises native override fallback (native source -> managed dest), URL-safe + no padding.
        val original = textBuffer("Many hands make light work. 1234567890")
        val size = original.remaining()
        val n = base64EncodedLength(size, padded = false)
        val b64 = BufferFactory.managed().allocate(n)
        original.encodeBase64Into(b64, urlSafe = true, padded = false)
        b64.resetForRead()

        val decoded = BufferFactory.managed().allocate(size)
        b64.decodeBase64Into(decoded)
        decoded.resetForRead()
        original.resetForRead()
        assertTrue(original.contentEquals(decoded))
    }

    // endregion

    // region position semantics + wrappers

    @Test
    fun absoluteEncodeDoesNotChangeSourcePosition() {
        val src = textBuffer("foobar")
        val dest = BufferFactory.Default.allocate(4)
        src.encodeBase64Into(dest, offset = 3, length = 3) // "bar"
        dest.resetForRead()
        assertEquals("YmFy", dest.readString(4))
        assertEquals(0, src.position())
    }

    @Test
    fun roundTripsThroughPooledBufferWrappers() {
        BufferPool().let { pool ->
            val src = pool.acquire(8)
            src.writeString("foobar")
            src.resetForRead()

            val b64 = pool.acquire(8)
            src.encodeBase64Into(b64)
            b64.resetForRead()
            assertEquals("Zm9vYmFy", b64.readString(8))
            b64.resetForRead()

            val decoded = pool.acquire(8)
            b64.decodeBase64Into(decoded)
            decoded.resetForRead()
            assertEquals("foobar", decoded.readString(6))

            pool.release(src)
            pool.release(b64)
            pool.release(decoded)
            pool.clear()
        }
    }

    // endregion
}
