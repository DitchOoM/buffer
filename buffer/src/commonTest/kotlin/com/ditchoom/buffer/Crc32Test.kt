package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Known-answer tests for [ReadBuffer.crc32] (standard CRC-32: ISO 3309 / zlib / PNG, the variant
 * whose "123456789" check value is 0xCBF43926).
 */
class Crc32Test {
    private fun bufferOf(text: String): PlatformBuffer {
        val buffer = BufferFactory.Default.allocate(maxOf(1, text.length))
        if (text.isNotEmpty()) buffer.writeString(text)
        buffer.resetForRead()
        buffer.setLimit(text.length)
        return buffer
    }

    @Test
    fun canonicalCheckValue() {
        // The standard CRC-32 check value (ISO 3309 / zlib).
        assertEquals(0xCBF43926u, bufferOf("123456789").crc32())
    }

    @Test
    fun emptyRangeIsZero() {
        assertEquals(0u, bufferOf("").crc32())
    }

    @Test
    fun singleByte() {
        assertEquals(0xE8B7BE43u, bufferOf("a").crc32())
    }

    @Test
    fun sentence() {
        assertEquals(0x414FA339u, bufferOf("The quick brown fox jumps over the lazy dog").crc32())
    }

    @Test
    fun subRangeMatchesWholeOfThatSlice() {
        // crc32(offset,length) over the middle "456" equals crc32() of just "456".
        val whole = bufferOf("123456789")
        assertEquals(bufferOf("456").crc32(), whole.crc32(3, 3))
    }

    @Test
    fun doesNotDisturbPosition() {
        val b = bufferOf("123456789")
        b.position(4)
        b.crc32(0, 9)
        assertEquals(4, b.position())
    }
}
