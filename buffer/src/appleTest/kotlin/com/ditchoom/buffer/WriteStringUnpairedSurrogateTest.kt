// Surrogate code units (0xD800..0xDFFF) and UTF-8 byte values are the subject under test.
@file:Suppress("MagicNumber")

package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the [MutableDataBuffer.writeString] behavior for ill-formed UTF-16 input.
 *
 * `NSString.dataUsingEncoding` returns `null` for any text containing an unpaired
 * surrogate (`allowLossyConversion` does not change this), which the previous `!!`
 * turned into a bare NPE. The fixed path substitutes U+FFFD for UTF-8 — matching
 * [ByteArrayBuffer], JS, WASM, and what [CharSequence.utf8Length] sizes — and throws
 * [IllegalArgumentException] for other charsets that cannot represent the text.
 */
class WriteStringUnpairedSurrogateTest {
    private val replacementBytes = byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte())

    /** [BufferFactory.Default] allocates [MutableDataBuffer] on Apple — the Foundation-backed path under test. */
    private fun allocateNativeAppleBuffer(size: Int): PlatformBuffer = BufferFactory.Default.allocate(size)

    @Test
    fun loneHighSurrogateSubstitutesReplacementChar() {
        val buffer = allocateNativeAppleBuffer(16)
        buffer.writeString("\uD800", Charset.UTF8)
        assertEquals(3, buffer.position(), "U+FFFD is three UTF-8 bytes")
        buffer.resetForRead()
        assertEquals(replacementBytes.toList(), buffer.readByteArray(3).toList())
    }

    @Test
    fun loneLowSurrogateSubstitutesReplacementChar() {
        val buffer = allocateNativeAppleBuffer(16)
        buffer.writeString("\uDC00", Charset.UTF8)
        assertEquals(3, buffer.position())
    }

    @Test
    fun charAfterUnpairedHighSurrogateIsStillWritten() {
        val buffer = allocateNativeAppleBuffer(16)
        buffer.writeString("\uD800€", Charset.UTF8)
        assertEquals(6, buffer.position(), "U+FFFD (3) + € (3)")
        assertEquals("\uD800€".utf8Length(), 6, "utf8Length must size exactly what was written")
    }

    @Test
    fun sliceWriteStringSubstitutesToo() {
        val buffer = allocateNativeAppleBuffer(16)
        val slice = buffer.slice()
        slice.writeString("\uD800A", Charset.UTF8)
        assertEquals(4, slice.position(), "U+FFFD (3) + A (1)")
    }

    @Test
    fun validSurrogatePairStillEncodesViaFoundation() {
        val buffer = allocateNativeAppleBuffer(16)
        buffer.writeString("😀", Charset.UTF8)
        assertEquals(4, buffer.position())
        buffer.resetForRead()
        assertEquals("😀", buffer.readString(4, Charset.UTF8))
    }

    @Test
    fun unrepresentableTextInNonUtf8CharsetThrowsInsteadOfCrashing() {
        val buffer = allocateNativeAppleBuffer(16)
        assertFailsWith<IllegalArgumentException> {
            buffer.writeString("€", Charset.ASCII)
        }
    }
}
