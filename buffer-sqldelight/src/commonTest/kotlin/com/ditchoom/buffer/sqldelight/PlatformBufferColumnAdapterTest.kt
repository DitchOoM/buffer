package com.ditchoom.buffer.sqldelight

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class PlatformBufferColumnAdapterTest {
    private val adapter = PlatformBufferColumnAdapter.Default

    @Test
    fun roundTripsBlobContent() {
        for (size in intArrayOf(0, 1, 8, 255, 4096)) {
            val original = ByteArray(size) { ((it * 31 + 7) and 0xFF).toByte() }
            val buffer = adapter.decode(original)
            val encoded = adapter.encode(buffer)
            assertContentEquals(original, encoded, "size=$size")
        }
    }

    @Test
    fun decodeAliasesTheDriverArray() {
        // Read side is copy-free: the returned buffer shares storage with the driver's ByteArray,
        // so a write through the buffer is visible in the original array.
        val bytes = byteArrayOf(1, 2, 3)
        val buffer = adapter.decode(bytes)
        buffer.set(0, 0x7F.toByte())
        assertEquals(0x7F.toByte(), bytes[0], "decode must alias (no extra copy)")
    }

    @Test
    fun encodeCopiesSoLaterBufferMutationsDoNotCorruptTheBoundValue() {
        // Write side must copy: the array handed to bindBytes has to be independent of the caller's
        // buffer, or a later mutation would silently rewrite an already-bound BLOB.
        val buffer = BufferFactory.Default.wrap(byteArrayOf(1, 2, 3))
        val encoded = adapter.encode(buffer)
        buffer.set(0, 0x7F.toByte())
        assertEquals(1.toByte(), encoded[0], "encode must copy, not alias the buffer's storage")
    }

    @Test
    fun encodeDoesNotConsumeTheBuffer() {
        val buffer = BufferFactory.Default.wrap(byteArrayOf(1, 2, 3, 4))
        val positionBefore = buffer.position()
        val remainingBefore = buffer.remaining()

        val first = adapter.encode(buffer)
        assertEquals(positionBefore, buffer.position(), "encode must not advance position")
        assertEquals(remainingBefore, buffer.remaining())

        // Non-destructive means a second encode yields the same bytes.
        val second = adapter.encode(buffer)
        assertContentEquals(first, second)
        assertNotSame(first, second, "each encode returns a fresh copy")
    }

    @Test
    fun encodeUsesOnlyRemainingBytes() {
        // A buffer positioned mid-way encodes just position..limit — the BLOB payload convention.
        val buffer = BufferFactory.Default.wrap(byteArrayOf(10, 20, 30, 40))
        buffer.position(2)
        assertContentEquals(byteArrayOf(30, 40), adapter.encode(buffer))
        assertEquals(2, buffer.position(), "position restored after encode")
    }
}
