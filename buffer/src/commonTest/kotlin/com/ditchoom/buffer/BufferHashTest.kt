package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for the fast content digest (hashRange / hash64) and range comparison (regionEquals)
 * added to [ReadBuffer].
 */
class BufferHashTest {
    private fun bufferOf(text: String): PlatformBuffer {
        val buffer = BufferFactory.Default.allocate(text.length)
        buffer.writeString(text)
        buffer.resetForRead()
        return buffer
    }

    // region hashRange / hash64

    @Test
    fun sameContentHashesEqual() {
        val a = bufferOf("Hamburg")
        val b = bufferOf("Hamburg")
        assertEquals(a.hashRange(0, 7), b.hashRange(0, 7))
    }

    @Test
    fun differentContentHashesDiffer() {
        val a = bufferOf("Hamburg")
        val b = bufferOf("Bulawayo")
        assertNotEquals(a.hashRange(0, 7), b.hashRange(0, 8))
    }

    @Test
    fun hashesLongNamesThroughBulkAndTail() {
        // 12 bytes: exercises one 8-byte bulk word + a 4-byte tail.
        val a = bufferOf("Saint-Pierre")
        val b = bufferOf("Saint-Pierre")
        assertEquals(a.hashRange(0, 12), b.hashRange(0, 12))
    }

    @Test
    fun hash64MatchesHashRangeOverRemaining() {
        val b = bufferOf("Reykjavik")
        assertEquals(b.hashRange(b.position(), b.remaining()), b.hash64())
    }

    @Test
    fun hashRangeDoesNotChangePosition() {
        val b = bufferOf("Casablanca")
        val startPos = b.position()
        b.hashRange(0, 10)
        b.hash64()
        assertEquals(startPos, b.position())
    }

    @Test
    fun hashSubRangeWithinLine() {
        val line = "Hamburg;-12.3"
        val full = bufferOf(line)
        val nameOnly = bufferOf("Hamburg")
        assertEquals(nameOnly.hashRange(0, 7), full.hashRange(0, 7))
    }

    // region regionEquals

    @Test
    fun regionEqualsMatchingRanges() {
        val a = bufferOf("Hamburg")
        val b = bufferOf("Hamburg")
        assertTrue(a.regionEquals(0, b, 0, 7))
    }

    @Test
    fun regionEqualsMismatchedRanges() {
        val a = bufferOf("Hamburg")
        val b = bufferOf("HamburX") // differs in the last byte
        assertFalse(a.regionEquals(0, b, 0, 7))
    }

    @Test
    fun regionEqualsSubRangeWithinLine() {
        val line = "Hamburg;-12.3"
        val full = bufferOf(line)
        val name = bufferOf("Hamburg")
        // Name region at offset 0, length 7 should equal the standalone name.
        assertTrue(full.regionEquals(0, name, 0, 7))
        // The temperature region should not equal the name.
        assertFalse(full.regionEquals(8, name, 0, 5))
    }

    @Test
    fun regionEqualsLongRangeThroughBulkPath() {
        val a = bufferOf("Saint-Pierre-and-Miquelon")
        val b = bufferOf("Saint-Pierre-and-Miquelon")
        assertTrue(a.regionEquals(0, b, 0, 25))
    }

    // region bufferHashCode (bulk 8-byte path) — must stay bit-identical to the naive 31-multiplier loop

    private fun naiveHash(b: PlatformBuffer): Int {
        var h = 1
        for (i in b.position() until b.limit()) h = 31 * h + b[i].toInt()
        return h
    }

    @Test
    fun bufferHashCodeBulkMatchesNaiveAcrossWordBoundary() {
        // 27 bytes: exercises three 8-byte bulk words + a 3-byte tail.
        val b = bufferOf("Saint-Pierre-and-Miquelon!!")
        assertEquals(naiveHash(b), bufferHashCode(b))
    }

    @Test
    fun bufferHashCodeShortStaysInTail() {
        val b = bufferOf("Hi") // < 8 bytes: only the tail loop runs
        assertEquals(naiveHash(b), bufferHashCode(b))
    }

    @Test
    fun bufferHashCodeIndependentOfByteOrder() {
        // get(i) is byte-order-independent, so the content hash must match regardless of byteOrder.
        val big = BufferFactory.Default.allocate(16, byteOrder = ByteOrder.BIG_ENDIAN)
        val little = BufferFactory.Default.allocate(16, byteOrder = ByteOrder.LITTLE_ENDIAN)
        for (buf in listOf(big, little)) {
            buf.writeString("HamburgBulawayo") // 15 bytes: one bulk word + 7-byte tail
            buf.resetForRead()
        }
        assertEquals(bufferHashCode(big), bufferHashCode(little))
        assertEquals(naiveHash(big), bufferHashCode(little))
    }
}
