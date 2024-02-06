@file:Suppress("EXPERIMENTAL_UNSIGNED_LITERALS")

package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FragmentedReadBufferTests {

    @Test
    fun readByteFromFirstBuffer() {
        val expectedFirstByte = Byte.MAX_VALUE
        val first = PlatformBuffer.allocate(1)
        first.writeByte(expectedFirstByte)
        first.resetForRead()
        val second = PlatformBuffer.allocate(1)
        val expectedSecondByte = Byte.MIN_VALUE
        second.writeByte(expectedSecondByte)
        second.resetForRead()

        val composableBuffer = FragmentedReadBuffer(first, second)
        assertEquals(expectedFirstByte, composableBuffer.readByte())
    }

    @Test
    fun readByteFromSecondBuffer() {
        val expectedFirstByte = Byte.MAX_VALUE
        val first = PlatformBuffer.allocate(1)
        first.writeByte(expectedFirstByte)
        first.resetForRead()
        val second = PlatformBuffer.allocate(1)
        val expectedSecondByte = Byte.MIN_VALUE
        second.writeByte(expectedSecondByte)
        second.resetForRead()

        val composableBuffer = FragmentedReadBuffer(first, second)
        composableBuffer.position(1)
        assertEquals(expectedSecondByte, composableBuffer.readByte())
    }

    @Test
    fun readShort() {
        val first = PlatformBuffer.allocate(Short.SIZE_BYTES)
        first.writeShort(1.toShort())
        first.resetForRead()
        val second = PlatformBuffer.allocate(Short.SIZE_BYTES)
        second.writeShort(2.toShort())
        second.resetForRead()
        val composableBuffer = FragmentedReadBuffer(first, second)
        assertEquals(1, composableBuffer.readShort())
        assertEquals(2, composableBuffer.readShort())
    }

    @Test
    fun readInt() {
        val first = PlatformBuffer.allocate(Int.SIZE_BYTES)
        first.writeInt(1)
        first.resetForRead()
        val second = PlatformBuffer.allocate(Int.SIZE_BYTES)
        second.writeInt(2)
        second.resetForRead()
        val composableBuffer = FragmentedReadBuffer(first, second)
        assertEquals(1, composableBuffer.readInt())
        assertEquals(2, composableBuffer.readInt())
    }

    @Test
    fun readLong() {
        val first = PlatformBuffer.allocate(Long.SIZE_BYTES)
        first.writeLong(1L)
        first.resetForRead()
        val second = PlatformBuffer.allocate(Long.SIZE_BYTES)
        second.writeLong(2L)
        second.resetForRead()
        val composableBuffer = FragmentedReadBuffer(first, second)
        assertEquals(1L, composableBuffer.readLong())
        assertEquals(2L, composableBuffer.readLong())
    }

    @Test
    fun readBytesFromThreeBuffers() {
        val expectedFirstByte = Byte.MAX_VALUE
        val first = PlatformBuffer.allocate(1)
        first.writeByte(expectedFirstByte)
        first.resetForRead()
        val second = PlatformBuffer.allocate(1)
        val expectedSecondByte = 6.toByte()
        second.writeByte(expectedSecondByte)
        second.resetForRead()
        val third = PlatformBuffer.allocate(1)
        val expectedThirdByte = Byte.MIN_VALUE
        third.writeByte(expectedThirdByte)
        third.resetForRead()

        val composableBuffer = arrayListOf(first, second, third).toComposableBuffer()
        assertEquals(expectedFirstByte, composableBuffer.readByte())
        assertEquals(expectedSecondByte, composableBuffer.readByte())
        assertEquals(expectedThirdByte, composableBuffer.readByte())
    }

    @Test
    fun readBytesFromFourBuffers() {
        val expectedFirstByte = Byte.MAX_VALUE
        val first = PlatformBuffer.allocate(1)
        first.writeByte(expectedFirstByte)
        first.resetForRead()
        val second = PlatformBuffer.allocate(1)
        val expectedSecondByte = 6.toByte()
        second.writeByte(expectedSecondByte)
        second.resetForRead()
        val third = PlatformBuffer.allocate(1)
        val expectedThirdByte = 12.toByte()
        third.writeByte(expectedThirdByte)
        third.resetForRead()

        val fourth = PlatformBuffer.allocate(1)
        val expectedFourthByte = Byte.MIN_VALUE
        fourth.writeByte(expectedFourthByte)
        fourth.resetForRead()

        val composableBuffer = arrayListOf(first, second, third, fourth).toComposableBuffer()
        assertEquals(expectedFirstByte, composableBuffer.readByte())
        assertEquals(expectedSecondByte, composableBuffer.readByte())
        assertEquals(expectedThirdByte, composableBuffer.readByte())
        assertEquals(expectedFourthByte, composableBuffer.readByte())
    }

    @Test
    fun readBytesFromFiveBuffers() {
        val expectedFirstByte = Byte.MAX_VALUE
        val first = PlatformBuffer.allocate(1)
        first.writeByte(expectedFirstByte)
        first.resetForRead()
        val second = PlatformBuffer.allocate(1)
        val expectedSecondByte = 6.toByte()
        second.writeByte(expectedSecondByte)
        second.resetForRead()
        val third = PlatformBuffer.allocate(1)
        val expectedThirdByte = (-1).toByte()
        third.writeByte(expectedThirdByte)
        third.resetForRead()

        val fourth = PlatformBuffer.allocate(1)
        val expectedFourthByte = 0.toByte()
        fourth.writeByte(expectedFourthByte)
        fourth.resetForRead()

        val fifth = PlatformBuffer.allocate(1)
        val expectedFifthByte = Byte.MIN_VALUE
        fifth.writeByte(expectedFifthByte)
        fifth.resetForRead()

        val composableBuffer = arrayListOf(first, second, third, fourth, fifth).toComposableBuffer()
        assertEquals(expectedFirstByte, composableBuffer.readByte())
        assertEquals(expectedSecondByte, composableBuffer.readByte())
        assertEquals(expectedThirdByte, composableBuffer.readByte())
        assertEquals(expectedFourthByte, composableBuffer.readByte())
        assertEquals(expectedFifthByte, composableBuffer.readByte())
    }

    @Test
    fun readUByteFromFirstBuffer() {
        val expectedFirstUByte = UByte.MAX_VALUE
        val first = PlatformBuffer.allocate(1)
        first.writeUByte(expectedFirstUByte)
        first.resetForRead()
        val second = PlatformBuffer.allocate(1)
        val expectedSecondUByte = UByte.MIN_VALUE
        second.writeUByte(expectedSecondUByte)
        second.resetForRead()

        val composableBuffer = FragmentedReadBuffer(first, second)
        assertEquals(expectedFirstUByte, composableBuffer.readUnsignedByte())
    }

    @Test
    fun readUByteeFromSecondBuffer() {
        val expectedFirstUByte = UByte.MAX_VALUE
        val first = PlatformBuffer.allocate(1)
        first.writeUByte(expectedFirstUByte)
        first.resetForRead()
        val second = PlatformBuffer.allocate(1)
        val expectedSecondUByte = UByte.MIN_VALUE
        second.writeUByte(expectedSecondUByte)
        second.resetForRead()

        val composableBuffer = FragmentedReadBuffer(first, second)
        composableBuffer.position(1)
        assertEquals(expectedSecondUByte, composableBuffer.readUnsignedByte())
    }

    @Test
    fun readUByteFromThreeBuffers() {
        val expectedFirstUByte = UByte.MAX_VALUE
        val first = PlatformBuffer.allocate(1)
        first.writeUByte(expectedFirstUByte)
        first.resetForRead()
        val second = PlatformBuffer.allocate(1)
        val expectedSecondUByte = 6.toUByte()
        second.writeUByte(expectedSecondUByte)
        second.resetForRead()
        val third = PlatformBuffer.allocate(1)
        val expectedThirdUByte = UByte.MIN_VALUE
        third.writeUByte(expectedThirdUByte)
        third.resetForRead()

        val composableBuffer = arrayListOf(first, second, third).toComposableBuffer()
        assertEquals(expectedFirstUByte, composableBuffer.readUnsignedByte())
        assertEquals(expectedSecondUByte, composableBuffer.readUnsignedByte())
        assertEquals(expectedThirdUByte, composableBuffer.readUnsignedByte())
    }

    @Test
    fun readUByteFromFourBuffers() {
        val expectedFirstUByte = UByte.MAX_VALUE
        val first = PlatformBuffer.allocate(1)
        first.writeUByte(expectedFirstUByte)
        first.resetForRead()
        val second = PlatformBuffer.allocate(1)
        val expectedSecondUByte = 6.toUByte()
        second.writeUByte(expectedSecondUByte)
        second.resetForRead()
        val third = PlatformBuffer.allocate(1)
        val expectedThirdUByte = 12.toUByte()
        third.writeUByte(expectedThirdUByte)
        third.resetForRead()

        val fourth = PlatformBuffer.allocate(1)
        val expectedFourthUByte = UByte.MIN_VALUE
        fourth.writeUByte(expectedFourthUByte)
        fourth.resetForRead()

        val composableBuffer = arrayListOf(first, second, third, fourth).toComposableBuffer()
        assertEquals(expectedFirstUByte, composableBuffer.readUnsignedByte())
        assertEquals(expectedSecondUByte, composableBuffer.readUnsignedByte())
        assertEquals(expectedThirdUByte, composableBuffer.readUnsignedByte())
        assertEquals(expectedFourthUByte, composableBuffer.readUnsignedByte())
    }

    @Test
    fun readUByteFromFiveBuffers() {
        val expectedFirstUByte = UByte.MAX_VALUE
        val first = PlatformBuffer.allocate(1)
        first.writeUByte(expectedFirstUByte)
        first.resetForRead()
        val second = PlatformBuffer.allocate(1)
        val expectedSecondUByte = 6.toUByte()
        second.writeUByte(expectedSecondUByte)
        second.resetForRead()
        val third = PlatformBuffer.allocate(1)
        val expectedThirdUByte = (-1).toUByte()
        third.writeUByte(expectedThirdUByte)
        third.resetForRead()

        val fourth = PlatformBuffer.allocate(1)
        val expectedFourthUByte = 0.toUByte()
        fourth.writeUByte(expectedFourthUByte)
        fourth.resetForRead()

        val fifth = PlatformBuffer.allocate(1)
        val expectedFifthUByte = UByte.MIN_VALUE
        fifth.writeUByte(expectedFifthUByte)
        fifth.resetForRead()

        val composableBuffer = arrayListOf(first, second, third, fourth, fifth).toComposableBuffer()
        assertEquals(expectedFirstUByte, composableBuffer.readUnsignedByte())
        assertEquals(expectedSecondUByte, composableBuffer.readUnsignedByte())
        assertEquals(expectedThirdUByte, composableBuffer.readUnsignedByte())
        assertEquals(expectedFourthUByte, composableBuffer.readUnsignedByte())
        assertEquals(expectedFifthUByte, composableBuffer.readUnsignedByte())
    }

    @Test
    fun readUnsignedShortFromFirstBuffer() {
        val expectedFirstUShort = UShort.MAX_VALUE
        val first = PlatformBuffer.allocate(UShort.SIZE_BYTES)
        first.writeUShort(expectedFirstUShort)
        first.resetForRead()

        val second = PlatformBuffer.allocate(UShort.SIZE_BYTES)
        val expectedSecondUShort = UShort.MIN_VALUE
        second.writeUShort(expectedSecondUShort)
        second.resetForRead()

        val composableBuffer = FragmentedReadBuffer(first, second)
        assertEquals(expectedFirstUShort, composableBuffer.readUnsignedShort())
        assertEquals(expectedSecondUShort, composableBuffer.readUnsignedShort())
    }

    @Test
    fun readUnsignedShortFromSecondBuffer() {
        val expectedFirstUShort = UShort.MAX_VALUE
        val first = PlatformBuffer.allocate(UShort.SIZE_BYTES)
        first.writeUShort(expectedFirstUShort)
        first.resetForRead()
        val second = PlatformBuffer.allocate(UShort.SIZE_BYTES)
        val expectedSecondUShort = UShort.MIN_VALUE
        second.writeUShort(expectedSecondUShort)
        second.resetForRead()

        val composableBuffer = FragmentedReadBuffer(first, second)
        composableBuffer.position(UShort.SIZE_BYTES)
        assertEquals(expectedSecondUShort, composableBuffer.readUnsignedShort())
    }

    @Test
    fun readUnsignedShortsFromThreeBuffers() {
        val expectedFirstUShort = UShort.MAX_VALUE
        val first = PlatformBuffer.allocate(UShort.SIZE_BYTES)
        first.writeUShort(expectedFirstUShort)
        first.resetForRead()
        val second = PlatformBuffer.allocate(UShort.SIZE_BYTES)
        val expectedSecondUShort = 6.toUShort()
        second.writeUShort(expectedSecondUShort)
        second.resetForRead()
        val third = PlatformBuffer.allocate(UShort.SIZE_BYTES)
        val expectedThirdUShort = UShort.MIN_VALUE
        third.writeUShort(expectedThirdUShort)
        third.resetForRead()

        val composableBuffer = arrayListOf(first, second, third).toComposableBuffer()
        assertEquals(expectedFirstUShort, composableBuffer.readUnsignedShort())
        assertEquals(expectedSecondUShort, composableBuffer.readUnsignedShort())
        assertEquals(expectedThirdUShort, composableBuffer.readUnsignedShort())
    }

    @Test
    fun readUnsignedShortsFromFourBuffers() {
        val expectedFirstUShort = UShort.MAX_VALUE
        val first = PlatformBuffer.allocate(UShort.SIZE_BYTES)
        first.writeUShort(expectedFirstUShort)
        first.resetForRead()
        val second = PlatformBuffer.allocate(UShort.SIZE_BYTES)
        val expectedSecondUShort = 6.toUShort()
        second.writeUShort(expectedSecondUShort)
        second.resetForRead()
        val third = PlatformBuffer.allocate(UShort.SIZE_BYTES)
        val expectedThirdUShort = 12.toUShort()
        third.writeUShort(expectedThirdUShort)
        third.resetForRead()

        val fourth = PlatformBuffer.allocate(UShort.SIZE_BYTES)
        val expectedFourthUShort = UShort.MIN_VALUE
        fourth.writeUShort(expectedFourthUShort)
        fourth.resetForRead()

        val composableBuffer = arrayListOf(first, second, third, fourth).toComposableBuffer()
        assertEquals(expectedFirstUShort, composableBuffer.readUnsignedShort())
        assertEquals(expectedSecondUShort, composableBuffer.readUnsignedShort())
        assertEquals(expectedThirdUShort, composableBuffer.readUnsignedShort())
        assertEquals(expectedFourthUShort, composableBuffer.readUnsignedShort())
    }

    @Test
    fun readUnsignedShortsFromFiveBuffers() {
        val expectedFirstUShort = UShort.MAX_VALUE
        val first = PlatformBuffer.allocate(UShort.SIZE_BYTES)
        first.writeUShort(expectedFirstUShort)
        first.resetForRead()
        val second = PlatformBuffer.allocate(UShort.SIZE_BYTES)
        val expectedSecondUShort = 6.toUShort()
        second.writeUShort(expectedSecondUShort)
        second.resetForRead()
        val third = PlatformBuffer.allocate(UShort.SIZE_BYTES)
        val expectedThirdUShort = (-1).toUShort()
        third.writeUShort(expectedThirdUShort)
        third.resetForRead()

        val fourth = PlatformBuffer.allocate(UShort.SIZE_BYTES)
        val expectedFourthUShort = 0.toUShort()
        fourth.writeUShort(expectedFourthUShort)
        fourth.resetForRead()

        val fifth = PlatformBuffer.allocate(UShort.SIZE_BYTES)
        val expectedFifthUShort = UShort.MIN_VALUE
        fifth.writeUShort(expectedFifthUShort)
        fifth.resetForRead()

        val composableBuffer = arrayListOf(first, second, third, fourth, fifth).toComposableBuffer()
        assertEquals(expectedFirstUShort, composableBuffer.readUnsignedShort())
        assertEquals(expectedSecondUShort, composableBuffer.readUnsignedShort())
        assertEquals(expectedThirdUShort, composableBuffer.readUnsignedShort())
        assertEquals(expectedFourthUShort, composableBuffer.readUnsignedShort())
        assertEquals(expectedFifthUShort, composableBuffer.readUnsignedShort())
    }

    @Test
    fun readFragmentedStringFromThreeBuffers() {
        val expectedString = "yolo-swag-lyfestyle"
        val utf8length = expectedString.toReadBuffer(Charset.UTF8).limit()
        val composableBuffer = expectedString
            .split(Regex("(?=-)"))
            .map { it.toReadBuffer(Charset.UTF8) }
            .toComposableBuffer()
        val actual = composableBuffer.readString(utf8length, Charset.UTF8)
        assertEquals(expectedString, actual)
    }

    @Test
    fun utf8Line() {
        val buffers = arrayOf("yolo\r\n", "\nsw\n\r\nag", "\r\nli\n\r\nfe\r\nstyle\r\n")
        val composableBuffer = buffers.map { it.toReadBuffer(Charset.UTF8) }.toComposableBuffer()
        assertEquals("yolo", composableBuffer.readUtf8Line().toString())
        assertEquals("", composableBuffer.readUtf8Line().toString())
        assertEquals("sw", composableBuffer.readUtf8Line().toString())
        assertEquals("", composableBuffer.readUtf8Line().toString())
        assertEquals("ag", composableBuffer.readUtf8Line().toString())
        assertEquals("li", composableBuffer.readUtf8Line().toString())
        assertEquals("", composableBuffer.readUtf8Line().toString())
        assertEquals("fe", composableBuffer.readUtf8Line().toString())
        assertEquals("style", composableBuffer.readUtf8Line().toString())
        assertEquals("", composableBuffer.readUtf8Line().toString())
        assertTrue { composableBuffer.remaining() == 0 }
    }

    @Test
    fun largeFragmentedBuffer() {
        val buffers = mutableListOf<ReadBuffer>()
        var indexCount = 0
        do { // 64 byte chunks
            val buffer = PlatformBuffer.allocate(64)
            repeat(64 / 4) {
                buffer.writeInt(indexCount++)
            }
            buffers += buffer
        } while (indexCount < 1024 * 1024)
        val fragmentedBuffer = buffers.toComposableBuffer() as FragmentedReadBuffer
        fragmentedBuffer.resetForRead()
        repeat(indexCount) {
            assertEquals(it, fragmentedBuffer.readInt())
        }
        assertEquals(0, fragmentedBuffer.remaining())
        fragmentedBuffer.resetForRead()
        val combined = fragmentedBuffer.toSingleBuffer()
        assertEquals(indexCount * 4, combined.remaining())
        repeat(indexCount) {
            assertEquals(it, combined.readInt())
        }
        assertEquals(0, combined.remaining())
    }
}
