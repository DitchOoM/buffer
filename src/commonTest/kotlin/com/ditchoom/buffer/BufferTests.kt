@file:Suppress("EXPERIMENTAL_UNSIGNED_LITERALS")

package com.ditchoom.buffer

import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExperimentalUnsignedTypes
class BufferTests {

    @Test
    fun slice() {
        val platformBuffer = PlatformBuffer.allocate(3u)
        platformBuffer.write((-1).toByte())
        platformBuffer.resetForRead()
        val slicedBuffer = platformBuffer.slice()
        assertEquals(0u, slicedBuffer.position())
        assertEquals(1u, slicedBuffer.limit())
        assertEquals(-1, slicedBuffer.readByte())
        assertEquals(1u, slicedBuffer.position())
        assertEquals(1u, slicedBuffer.limit())
    }


    @Test
    fun sliceAndReadUtf8() {
        val expected = "test"
        // the first two bytes are not visible characters
        val bytes = byteArrayOf(-126,4,
            expected[0].code.toByte(),
            expected[1].code.toByte(),
            expected[2].code.toByte(),
            expected[3].code.toByte())
        val platformBuffer = PlatformBuffer.allocate(bytes.size.toUInt())
        platformBuffer.write(bytes)
        platformBuffer.position(2)
        assertEquals(expected, platformBuffer.readUtf8(4).toString())
    }

    @Test
    fun sliceFragmented() {
        val platformBuffer1 = PlatformBuffer.allocate(3u)
        platformBuffer1.write(1.toByte())
        platformBuffer1.resetForRead()

        val platformBuffer2 = PlatformBuffer.allocate(3u)
        platformBuffer2.write(2.toByte())
        platformBuffer2.resetForRead()

        val fragmentedBuffer = FragmentedReadBuffer(platformBuffer1, platformBuffer2)
        val slicedBuffer = fragmentedBuffer.slice()
        assertEquals(0u, slicedBuffer.position())
        assertEquals(2u, slicedBuffer.limit())
        assertEquals(1, slicedBuffer.readByte())
        assertEquals(2, slicedBuffer.readByte())
        assertEquals(2u, slicedBuffer.position())
        assertEquals(2u, slicedBuffer.limit())
    }

    @Test
    fun byte() {
        val platformBuffer = PlatformBuffer.allocate(1u)
        val byte = (-1).toByte()
        platformBuffer.write(byte)
        platformBuffer.resetForRead()
        assertEquals(byte.toInt(), platformBuffer.readByte().toInt())
    }

    @Test
    fun byteArray() {
        val size = 200
        val platformBuffer = PlatformBuffer.allocate(size.toUInt())
        val bytes = ByteArray(200) { -1 }
        platformBuffer.write(bytes)
        platformBuffer.resetForRead()
        val byteArray = platformBuffer.readByteArray(size.toUInt())
        assertEquals(bytes.count(), byteArray.count())
        var count = 0
        for (byte in bytes) {
            assertEquals(byte, byteArray[count++])
        }
    }

    @Test
    fun unsignedByte() {
        val platformBuffer = PlatformBuffer.allocate(1u)
        val byte = (-1).toUByte()
        platformBuffer.write(byte)
        platformBuffer.resetForRead()
        assertEquals(byte.toInt(), platformBuffer.readUnsignedByte().toInt())
    }

    @Test
    fun unsignedShort() {
        val platformBuffer = PlatformBuffer.allocate(2u)
        val uShort = UShort.MAX_VALUE.toInt() / 2
        platformBuffer.write(uShort.toUShort())
        platformBuffer.resetForRead()
        assertEquals(uShort, platformBuffer.readUnsignedShort().toInt())
        platformBuffer.resetForRead()
        val msb = platformBuffer.readByte()
        val lsb = platformBuffer.readByte()
        val value = ((0xff and msb.toInt() shl 8)
                or (0xff and lsb.toInt() shl 0)).toUShort()
        assertEquals(value.toInt(), uShort)
    }

    @Test
    fun allUShortValues() {
        val buffer = PlatformBuffer.allocate(UShort.MAX_VALUE.toUInt() * UShort.SIZE_BYTES.toUInt())
        (0 until UShort.MAX_VALUE.toInt()).forEach {
            buffer.write(it.toUShort())
        }
        buffer.resetForRead()
        (0 until UShort.MAX_VALUE.toInt()).forEach {
            assertEquals(it, buffer.readUnsignedShort().toInt())
        }
    }

    @Test
    fun unsignedShortHalf() {
        val platformBuffer = PlatformBuffer.allocate(2u)
        val uShort = (UShort.MAX_VALUE / 2u).toUShort()
        platformBuffer.write(uShort)
        platformBuffer.resetForRead()
        val actual = platformBuffer.readUnsignedShort().toInt()
        assertEquals(uShort.toInt(), actual)
        assertEquals(uShort.toString(), actual.toString())
    }

    @Test
    fun unsignedInt() {
        val platformBuffer = PlatformBuffer.allocate(4u)
        val uInt = (-1).toUInt()
        platformBuffer.write(uInt)
        platformBuffer.resetForRead()
        assertEquals(uInt.toLong(), platformBuffer.readUnsignedInt().toLong())
    }

    @Test
    fun unsignedIntHalf() {
        val platformBuffer = PlatformBuffer.allocate(4u)
        val uInt = Int.MAX_VALUE.toUInt() / 2u
        platformBuffer.write(uInt)
        platformBuffer.resetForRead()
        assertEquals(uInt.toLong(), platformBuffer.readUnsignedInt().toLong())
    }

    @Test
    fun long() {
        val platformBuffer = PlatformBuffer.allocate(Long.SIZE_BYTES.toUInt())
        val long = (1234).toLong()
        platformBuffer.write(long)
        platformBuffer.resetForRead()
        assertEquals(long, platformBuffer.readLong())
    }

    @Test
    fun float() {
        val platformBuffer = PlatformBuffer.allocate(Float.SIZE_BYTES.toUInt())
        val float = 123.456f
        platformBuffer.write(float)
        platformBuffer.resetForRead()
        // Note that in Kotlin/JS Float range is wider than "single format" bit layout can represent,
        // so some Float values may overflow, underflow or lose their accuracy after conversion to bits and back.
        assertTrue { (float - platformBuffer.readFloat()).absoluteValue < 0.00001f }
    }

    @Test
    fun double() {
        val platformBuffer = PlatformBuffer.allocate(Double.SIZE_BYTES.toUInt())
        val double = 123.456
        platformBuffer.write(double)
        platformBuffer.resetForRead()
        assertEquals(double, platformBuffer.readDouble())
    }

    @Test
    fun utf8String() {
        val string = "yolo swag lyfestyle"
        assertEquals(19, string.utf8Length().toInt())
        val platformBuffer = PlatformBuffer.allocate(19u)
        platformBuffer.writeUtf8(string)
        platformBuffer.resetForRead()
        val actual = platformBuffer.readUtf8(19u).toString()
        assertEquals(string.length, actual.length)
        assertEquals(string, actual)
    }


    @Test
    fun readUtf8LineSingle() {
        val text = "hello"
        val buffer = text.toBuffer()
        assertEquals("hello", buffer.readUtf8Line().toString())
        assertEquals(buffer.remaining(), 0u)
    }

    @Test
    fun readUtf8LineDouble() {
        val text = "hello\r\n"
        val buffer = text.toBuffer()
        assertEquals("hello", buffer.readUtf8Line().toString())
        assertEquals("", buffer.readUtf8Line().toString())
        assertEquals(buffer.remaining(), 0u)
    }

    @Test
    fun readUtf8LineStart() {
        val text = "\r\nhello"
        val buffer = text.toBuffer()
        assertEquals("", buffer.readUtf8Line().toString())
        assertEquals("hello", buffer.readUtf8Line().toString())
        assertEquals(buffer.remaining(), 0u)
    }

    @Test
    fun readUtf8LineStartN() {
        val text = "\nhello"
        val buffer = text.toBuffer()
        assertEquals("", buffer.readUtf8Line().toString())
        assertEquals("hello", buffer.readUtf8Line().toString())
        assertEquals(buffer.remaining(), 0u)
    }

    @Test
    fun readUtf8LineMix() {
        val text = "\nhello\r\nhello\nhello\r\n"
        val buffer = text.toBuffer()
        assertEquals("", buffer.readUtf8Line().toString())
        assertEquals("hello", buffer.readUtf8Line().toString())
        assertEquals("hello", buffer.readUtf8Line().toString())
        assertEquals("hello", buffer.readUtf8Line().toString())
        assertEquals("", buffer.readUtf8Line().toString())
        assertEquals(buffer.remaining(), 0u)
    }


    @Test
    fun readUtf8LineMixMulti() {
        val text = "\nhello\r\n\nhello\n\nhello\r\n"
        val buffer = text.toBuffer()
        assertEquals("", buffer.readUtf8Line().toString())
        assertEquals("hello", buffer.readUtf8Line().toString())
        assertEquals("", buffer.readUtf8Line().toString())
        assertEquals("hello", buffer.readUtf8Line().toString())
        assertEquals("", buffer.readUtf8Line().toString())
        assertEquals("hello", buffer.readUtf8Line().toString())
        assertEquals("", buffer.readUtf8Line().toString())
        assertEquals(buffer.remaining(), 0u)
    }

    @Test
    fun readUtf8Line() {
        val stringArray = "yolo swag lyfestyle".split(' ')
        assertEquals(3, stringArray.size)
        val newLineString = stringArray.joinToString("\r\n")
        val stringBuffer = newLineString.toBuffer()
        stringArray.forEach {
            val line = stringBuffer.readUtf8Line()
            assertEquals(it, line.toString())
        }
    }

    @Test
    fun endianWrite() {
        val littleEndian2 = PlatformBuffer.allocate(2u, ByteOrder.LITTLE_ENDIAN)
        littleEndian2.write(0x0102.toShort())
        littleEndian2.resetForRead()
        assertEquals(0x02u, littleEndian2.readUnsignedByte())
        assertEquals(0x01u, littleEndian2.readUnsignedByte())

        val bigEndian2 = PlatformBuffer.allocate(2u, ByteOrder.BIG_ENDIAN)
        bigEndian2.write(0x0102.toShort())
        bigEndian2.resetForRead()
        assertEquals(0x01u, bigEndian2.readUnsignedByte())
        assertEquals(0x02u, bigEndian2.readUnsignedByte())

        val littleEndian4 = PlatformBuffer.allocate(4u, ByteOrder.LITTLE_ENDIAN)
        littleEndian4.write(0x01020304)
        littleEndian4.resetForRead()
        assertEquals(0x04u, littleEndian4.readUnsignedByte())
        assertEquals(0x03u, littleEndian4.readUnsignedByte())
        assertEquals(0x02u, littleEndian4.readUnsignedByte())
        assertEquals(0x01u, littleEndian4.readUnsignedByte())

        val bigEndian4 = PlatformBuffer.allocate(4u, ByteOrder.BIG_ENDIAN)
        bigEndian4.write(0x01020304)
        bigEndian4.resetForRead()
        assertEquals(0x01u, bigEndian4.readUnsignedByte())
        assertEquals(0x02u, bigEndian4.readUnsignedByte())
        assertEquals(0x03u, bigEndian4.readUnsignedByte())
        assertEquals(0x04u, bigEndian4.readUnsignedByte())

        val littleEndian8 = PlatformBuffer.allocate(8u, ByteOrder.LITTLE_ENDIAN)
        littleEndian8.write(0x0102030405060708)
        littleEndian8.resetForRead()
        assertEquals(0x08u, littleEndian8.readUnsignedByte())
        assertEquals(0x07u, littleEndian8.readUnsignedByte())
        assertEquals(0x06u, littleEndian8.readUnsignedByte())
        assertEquals(0x05u, littleEndian8.readUnsignedByte())
        assertEquals(0x04u, littleEndian8.readUnsignedByte())
        assertEquals(0x03u, littleEndian8.readUnsignedByte())
        assertEquals(0x02u, littleEndian8.readUnsignedByte())
        assertEquals(0x01u, littleEndian8.readUnsignedByte())

        val bigEndian8 = PlatformBuffer.allocate(8u, ByteOrder.BIG_ENDIAN)
        bigEndian8.write(0x0102030405060708)
        bigEndian8.resetForRead()
        assertEquals(0x01u, bigEndian8.readUnsignedByte())
        assertEquals(0x02u, bigEndian8.readUnsignedByte())
        assertEquals(0x03u, bigEndian8.readUnsignedByte())
        assertEquals(0x04u, bigEndian8.readUnsignedByte())
        assertEquals(0x05u, bigEndian8.readUnsignedByte())
        assertEquals(0x06u, bigEndian8.readUnsignedByte())
        assertEquals(0x07u, bigEndian8.readUnsignedByte())
        assertEquals(0x08u, bigEndian8.readUnsignedByte())
    }

    @Test
    fun endianRead() {
        val littleEndian2 = PlatformBuffer.allocate(2u, ByteOrder.LITTLE_ENDIAN)
        littleEndian2.write(0x01.toByte())
        littleEndian2.write(0x02.toByte())
        littleEndian2.resetForRead()
        assertEquals(0x0201.toShort(), littleEndian2.readShort())

        val bigEndian2 = PlatformBuffer.allocate(2u, ByteOrder.BIG_ENDIAN)
        bigEndian2.write(0x01.toByte())
        bigEndian2.write(0x02.toByte())
        bigEndian2.resetForRead()
        assertEquals(0x0102.toShort(), bigEndian2.readShort())

        val littleEndian4 = PlatformBuffer.allocate(4u, ByteOrder.LITTLE_ENDIAN)
        littleEndian4.write(0x01.toByte())
        littleEndian4.write(0x02.toByte())
        littleEndian4.write(0x03.toByte())
        littleEndian4.write(0x04.toByte())
        littleEndian4.resetForRead()
        assertEquals(0x04030201, littleEndian4.readInt())

        val bigEndian4 = PlatformBuffer.allocate(4u, ByteOrder.BIG_ENDIAN)
        bigEndian4.write(0x01.toByte())
        bigEndian4.write(0x02.toByte())
        bigEndian4.write(0x03.toByte())
        bigEndian4.write(0x04.toByte())
        bigEndian4.resetForRead()
        assertEquals(0x01020304, bigEndian4.readInt())

        val littleEndian8 = PlatformBuffer.allocate(8u, ByteOrder.LITTLE_ENDIAN)
        littleEndian8.write(0x01.toByte())
        littleEndian8.write(0x02.toByte())
        littleEndian8.write(0x03.toByte())
        littleEndian8.write(0x04.toByte())
        littleEndian8.write(0x05.toByte())
        littleEndian8.write(0x06.toByte())
        littleEndian8.write(0x07.toByte())
        littleEndian8.write(0x08.toByte())
        littleEndian8.resetForRead()
        assertEquals(0x0807060504030201, littleEndian8.readLong())

        val bigEndian8 = PlatformBuffer.allocate(8u, ByteOrder.BIG_ENDIAN)
        bigEndian8.write(0x01.toByte())
        bigEndian8.write(0x02.toByte())
        bigEndian8.write(0x03.toByte())
        bigEndian8.write(0x04.toByte())
        bigEndian8.write(0x05.toByte())
        bigEndian8.write(0x06.toByte())
        bigEndian8.write(0x07.toByte())
        bigEndian8.write(0x08.toByte())
        bigEndian8.resetForRead()
        assertEquals(0x0102030405060708, bigEndian8.readLong())
    }

    @Test
    fun partialByteArray() {
        val byteArray = byteArrayOf(0,1,2,3,4,5,6,7,8,9)
        val partialArray = byteArray.sliceArray(2..6)
        val buffer = PlatformBuffer.allocate(partialArray.size.toUInt())
        buffer.write(byteArray, 2, 5)
        buffer.resetForRead()
        assertContentEquals(partialArray, buffer.readByteArray(5u))
    }

    @Test
    fun wrap() {
        val byteArray = byteArrayOf(0,1,2,3,4,5,6,7,8,9)
        val buffer = PlatformBuffer.wrap(byteArray)
        assertEquals(byteArray.size.toUInt(), buffer.remaining())
        assertContentEquals(byteArray, buffer.readByteArray(buffer.remaining()))
    }
}