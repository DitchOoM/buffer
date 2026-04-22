package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * SHA-1 test vectors sourced from:
 *   - RFC 3174 appendix A (test vectors)
 *   - FIPS 180-1 appendix A (original publication)
 *   - NIST CAVS / the Wikipedia SHA-1 summary for the empty-input case.
 *
 * Every test ends up asserting the [writeSha1Of] extension; the class
 * is an API, not a reference, so parity with well-known vectors is what
 * makes this worth having.
 */
class Sha1Test {
    private fun hex(b: ByteArray): String = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun digestOfString(s: String): String {
        val input = BufferFactory.Default.allocate(s.utf8ByteCount().coerceAtLeast(0))
        if (s.isNotEmpty()) input.writeString(s)
        input.resetForRead()

        val out = BufferFactory.Default.allocate(Sha1.DIGEST_SIZE)
        out.writeSha1Of(input)
        out.resetForRead()
        return hex(out.readByteArray(Sha1.DIGEST_SIZE))
    }

    @Test
    fun emptyInput() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", digestOfString(""))
    }

    @Test
    fun abcVector() {
        // FIPS 180-1 §A.1
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", digestOfString("abc"))
    }

    @Test
    fun rfc3174LongerVector() {
        // FIPS 180-1 §A.2 — 56 bytes, forces padding into a second block
        val s = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
        assertEquals("84983e441c3bd26ebaae4aa1f95129e5e54670f1", digestOfString(s))
    }

    @Test
    fun oneMillionAsciiA() {
        // FIPS 180-1 §A.3 — 1_000_000 × 'a'. Streams input across many blocks.
        val sha = Sha1()
        val chunkBuf = BufferFactory.Default.allocate(1024)
        for (i in 0 until 1024) chunkBuf.writeByte('a'.code.toByte())
        // Feed in 977 full chunks of 1024 + tail of 576 bytes → 1,000,448? adjust below
        // Simpler: feed byte-by-byte streams of length 1_000_000 via a single 1MB buffer.
        chunkBuf.resetForRead()
        val input = BufferFactory.Default.allocate(1_000_000)
        repeat(1_000_000) { input.writeByte('a'.code.toByte()) }
        input.resetForRead()
        val out = BufferFactory.Default.allocate(Sha1.DIGEST_SIZE)
        sha.update(input)
        sha.finish(out)
        out.resetForRead()
        assertEquals(
            "34aa973cd4c4daa4f61eeb2bdbad27316534016f",
            hex(out.readByteArray(Sha1.DIGEST_SIZE)),
        )
    }

    @Test
    fun webSocketAcceptKey() {
        // RFC 6455 §1.3 — worked example of Sec-WebSocket-Accept computation.
        //   client key: "dGhlIHNhbXBsZSBub25jZQ=="
        //   GUID:       "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        //   SHA-1(key || GUID) → this digest
        //   base64(digest) → "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        //   hex(digest)    → b37a4f2cc0624f1690f64606cf385945b2bec4ea
        //                    (derived from base64-decoding "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")
        val concatenated = "dGhlIHNhbXBsZSBub25jZQ==258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        assertEquals("b37a4f2cc0624f1690f64606cf385945b2bec4ea", digestOfString(concatenated))
    }

    @Test
    fun streamingMultipleUpdates() {
        // Split "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq" into
        // three updates; must produce the same digest as the one-shot variant.
        val sha = Sha1()
        val parts = listOf("abcdbcdecdefdefg", "efghfghighijhijkijkl", "jklmklmnlmnomnopnopq")
        for (part in parts) {
            val buf = BufferFactory.Default.allocate(part.length)
            buf.writeString(part)
            buf.resetForRead()
            sha.update(buf)
        }
        val out = BufferFactory.Default.allocate(Sha1.DIGEST_SIZE)
        sha.finish(out)
        out.resetForRead()
        assertEquals(
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            hex(out.readByteArray(Sha1.DIGEST_SIZE)),
        )
    }

    @Test
    fun resetAllowsReuse() {
        val sha = Sha1()
        val buf = BufferFactory.Default.allocate(3)
        buf.writeString("abc")
        buf.resetForRead()
        val out = BufferFactory.Default.allocate(Sha1.DIGEST_SIZE)
        sha.update(buf).finish(out)
        sha.reset()

        val buf2 = BufferFactory.Default.allocate(3)
        buf2.writeString("abc")
        buf2.resetForRead()
        val out2 = BufferFactory.Default.allocate(Sha1.DIGEST_SIZE)
        sha.update(buf2).finish(out2)

        out.resetForRead()
        out2.resetForRead()
        assertEquals(
            hex(out.readByteArray(Sha1.DIGEST_SIZE)),
            hex(out2.readByteArray(Sha1.DIGEST_SIZE)),
        )
    }

    @Test
    fun finishTwiceThrows() {
        val sha = Sha1()
        val out = BufferFactory.Default.allocate(Sha1.DIGEST_SIZE)
        sha.finish(out)
        val out2 = BufferFactory.Default.allocate(Sha1.DIGEST_SIZE)
        assertFailsWith<IllegalStateException> { sha.finish(out2) }
    }

    @Test
    fun updateAfterFinishThrows() {
        val sha = Sha1()
        val out = BufferFactory.Default.allocate(Sha1.DIGEST_SIZE)
        sha.finish(out)
        val input = BufferFactory.Default.allocate(4)
        input.writeString("abcd")
        input.resetForRead()
        assertFailsWith<IllegalStateException> { sha.update(input) }
    }

    @Test
    fun insufficientOutputSpaceThrows() {
        val sha = Sha1()
        val out = BufferFactory.Default.allocate(19) // one byte short
        assertFailsWith<BufferOverflowException> { sha.finish(out) }
    }
}
