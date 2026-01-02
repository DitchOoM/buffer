package com.ditchoom.buffer

import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BufferTests {
    @Test
    fun slice() {
        val platformBuffer = PlatformBuffer.allocate(3)
        platformBuffer.writeByte((-1).toByte())
        platformBuffer.resetForRead()
        val slicedBuffer = platformBuffer.slice()
        assertEquals(0, platformBuffer.position())
        assertEquals(1, platformBuffer.limit())
        assertEquals(0, slicedBuffer.position())
        assertEquals(1, slicedBuffer.limit())
        assertEquals(-1, slicedBuffer.readByte())
        assertEquals(1, slicedBuffer.position())
        assertEquals(1, slicedBuffer.limit())
    }

    @Test
    fun sharedMemoryAllocates() {
        val platformBuffer =
            PlatformBuffer.allocate(
                Byte.SIZE_BYTES + UByte.SIZE_BYTES +
                    Short.SIZE_BYTES + UShort.SIZE_BYTES +
                    Int.SIZE_BYTES + UInt.SIZE_BYTES +
                    Long.SIZE_BYTES + ULong.SIZE_BYTES,
                AllocationZone.SharedMemory,
            )
        testAllPrimitives(platformBuffer)
    }

    @Test
    fun absolute() {
        val platformBuffer =
            PlatformBuffer.allocate(
                Byte.SIZE_BYTES + UByte.SIZE_BYTES +
                    Short.SIZE_BYTES + UShort.SIZE_BYTES +
                    Int.SIZE_BYTES + UInt.SIZE_BYTES +
                    Long.SIZE_BYTES + ULong.SIZE_BYTES,
            )
        testAllPrimitives(platformBuffer)
    }

    private fun testAllPrimitives(platformBuffer: PlatformBuffer) {
        var index = 0
        platformBuffer[index] = Byte.MIN_VALUE
        index += Byte.SIZE_BYTES
        platformBuffer[index] = UByte.MAX_VALUE
        index += UByte.SIZE_BYTES

        platformBuffer[index] = Short.MIN_VALUE
        index += Short.SIZE_BYTES
        platformBuffer[index] = UShort.MAX_VALUE
        index += UShort.SIZE_BYTES

        platformBuffer[index] = Int.MIN_VALUE
        index += Int.SIZE_BYTES
        platformBuffer[index] = UInt.MAX_VALUE
        index += UInt.SIZE_BYTES

        platformBuffer[index] = Long.MIN_VALUE
        index += Long.SIZE_BYTES
        platformBuffer[index] = ULong.MAX_VALUE

        index = 0
        assertEquals(platformBuffer[index], Byte.MIN_VALUE, "absolute byte read")
        index += Byte.SIZE_BYTES
        assertEquals(platformBuffer.getUnsignedByte(index), UByte.MAX_VALUE, "absolute ubyte read")
        index += UByte.SIZE_BYTES

        assertEquals(platformBuffer.getShort(index), Short.MIN_VALUE, "absolute short read")
        index += Short.SIZE_BYTES
        assertEquals(platformBuffer.getUnsignedShort(index), UShort.MAX_VALUE, "absolute ushort read")
        index += UShort.SIZE_BYTES

        assertEquals(platformBuffer.getInt(index), Int.MIN_VALUE, "absolute int read")
        index += Int.SIZE_BYTES
        assertEquals(platformBuffer.getUnsignedInt(index), UInt.MAX_VALUE, "absolute uint read")
        index += UInt.SIZE_BYTES

        assertEquals(platformBuffer.getLong(index), Long.MIN_VALUE, "absolute long read")
        index += Long.SIZE_BYTES
        assertEquals(platformBuffer.getUnsignedLong(index), ULong.MAX_VALUE, "absolute ulong read")

        // double validate with relative reading
        assertEquals(0, platformBuffer.position(), "relative initial position")
        assertEquals(platformBuffer.readByte(), Byte.MIN_VALUE, "relative byte read")
        assertEquals(1, platformBuffer.position(), "relative after byte read position")
        assertEquals(platformBuffer.readUnsignedByte(), UByte.MAX_VALUE, "relative ubyte read")
        assertEquals(2, platformBuffer.position(), "relative after ubyte read position")

        assertEquals(platformBuffer.readShort(), Short.MIN_VALUE, "relative short read")
        assertEquals(4, platformBuffer.position(), "relative after short read position")
        assertEquals(platformBuffer.readUnsignedShort(), UShort.MAX_VALUE, "relative ushort read")
        assertEquals(6, platformBuffer.position(), "relative after ushort read position")

        assertEquals(platformBuffer.readInt(), Int.MIN_VALUE, "relative int read")
        assertEquals(10, platformBuffer.position(), "relative after int read position")
        assertEquals(platformBuffer.readUnsignedInt(), UInt.MAX_VALUE, "relative uint read")
        assertEquals(14, platformBuffer.position(), "relative after uint read position")

        assertEquals(platformBuffer.readLong(), Long.MIN_VALUE, "relative long read")
        assertEquals(22, platformBuffer.position(), "relative after long read position")
        assertEquals(platformBuffer.readUnsignedLong(), ULong.MAX_VALUE, "relative ulong read")
        assertEquals(30, platformBuffer.position(), "relative after ulong read position")
    }

    @Test
    fun readByte() {
        val platformBuffer = PlatformBuffer.allocate(3)
        platformBuffer.writeByte((-1).toByte())
        platformBuffer.resetForRead()
        val slicedBuffer = platformBuffer.readBytes(1)
        assertEquals(1, platformBuffer.position())
        assertEquals(1, platformBuffer.limit())
        assertEquals(0, slicedBuffer.position())
        assertEquals(1, slicedBuffer.limit())
        assertEquals(-1, slicedBuffer.readByte())
        assertEquals(1, slicedBuffer.position())
        assertEquals(1, slicedBuffer.limit())
    }

    @Test
    fun getByte() {
        val platformBuffer = PlatformBuffer.allocate(3)
        val expectedValue = (-1).toByte()
        platformBuffer[0] = expectedValue
        val valueRead = platformBuffer[0]
        assertEquals(0, platformBuffer.position())
        assertEquals(3, platformBuffer.limit())
        assertEquals(-1, valueRead)
    }

    @Test
    fun sliceAndReadUtf8() {
        val expected = "test"
        // the first two bytes are not visible characters
        val bytes =
            byteArrayOf(
                -126,
                4,
                expected[0].code.toByte(),
                expected[1].code.toByte(),
                expected[2].code.toByte(),
                expected[3].code.toByte(),
            )
        val platformBuffer = PlatformBuffer.allocate(bytes.size)
        platformBuffer.writeBytes(bytes)
        platformBuffer.position(2)
        assertEquals(expected, platformBuffer.readString(4, Charset.UTF8))
    }

    @Test
    fun sliceFragmented() {
        val platformBuffer1 = PlatformBuffer.allocate(3)
        platformBuffer1.writeByte(1.toByte())
        platformBuffer1.resetForRead()

        val platformBuffer2 = PlatformBuffer.allocate(3)
        platformBuffer2.writeByte((-1).toByte())
        platformBuffer2.resetForRead()

        val fragmentedBuffer = FragmentedReadBuffer(platformBuffer1, platformBuffer2)
        assertEquals(0, fragmentedBuffer.position())
        assertEquals(2, fragmentedBuffer.limit())
        assertEquals(1, fragmentedBuffer.readByte())
        assertEquals(1, fragmentedBuffer.position())
        assertEquals(2, fragmentedBuffer.limit())
        assertEquals(-1, fragmentedBuffer.readByte())
        assertEquals(2, fragmentedBuffer.position())
        assertEquals(2, fragmentedBuffer.limit())
        fragmentedBuffer.resetForRead()
        assertEquals(0, fragmentedBuffer.position())
        assertEquals(2, fragmentedBuffer.limit())
        val slicedBuffer = fragmentedBuffer.slice()
        assertEquals(0, slicedBuffer.position())
        assertEquals(2, slicedBuffer.limit())
        assertEquals(1, slicedBuffer.readByte())
        assertEquals(1, slicedBuffer.position())
        assertEquals(2, slicedBuffer.limit())
        assertEquals(-1, slicedBuffer.readByte())
        assertEquals(2, slicedBuffer.position())
        assertEquals(2, slicedBuffer.limit())
    }

    @Test
    fun byte() {
        val platformBuffer = PlatformBuffer.allocate(1)
        val byte = (-1).toByte()
        platformBuffer.writeByte(byte)
        platformBuffer.resetForRead()
        assertEquals(byte.toInt(), platformBuffer.readByte().toInt())
    }

    @Test
    fun byteArray() {
        val size = 200
        val platformBuffer = PlatformBuffer.allocate(size)
        val bytes = ByteArray(size) { -1 }
        platformBuffer.writeBytes(bytes)
        platformBuffer.resetForRead()
        val byteArray = platformBuffer.readByteArray(size)
        assertEquals(bytes.count(), byteArray.count())
        bytes.forEachIndexed { index, byte ->
            assertEquals(byte, byteArray[index], "Index $index")
        }
    }

    @Test
    fun relativeUnsignedByte() {
        val platformBuffer = PlatformBuffer.allocate(1)
        val byte = (-1).toUByte()
        platformBuffer.writeUByte(byte)
        platformBuffer.resetForRead()
        assertEquals(byte.toInt(), platformBuffer.readUnsignedByte().toInt())
        assertFalse(platformBuffer.hasRemaining())
    }

    @Test
    fun absoluteUnsignedByte() {
        val platformBuffer = PlatformBuffer.allocate(1)
        val byte = (-1).toUByte()
        platformBuffer[0] = byte
        assertEquals(byte, platformBuffer[0].toUByte())
        assertEquals(1, platformBuffer.remaining())
    }

    @Test
    fun relativeUnsignedShort() {
        val platformBuffer = PlatformBuffer.allocate(2)
        val uShort = UShort.MAX_VALUE.toInt() / 2
        platformBuffer.writeUShort(uShort.toUShort())
        platformBuffer.resetForRead()
        assertEquals(uShort, platformBuffer.readUnsignedShort().toInt())
        platformBuffer.resetForRead()
        val msb = platformBuffer.readByte()
        val lsb = platformBuffer.readByte()
        val value =
            (
                (0xff and msb.toInt() shl 8)
                    or (0xff and lsb.toInt() shl 0)
            ).toUShort()
        assertEquals(value.toInt(), uShort)
        assertEquals(0, platformBuffer.remaining())
    }

    @Test
    fun absoluteUnsignedShort() {
        val platformBuffer = PlatformBuffer.allocate(2)
        val uShort = UShort.MAX_VALUE
        platformBuffer[0] = uShort
        assertEquals(uShort, platformBuffer.getUnsignedShort(0))
        val msb = platformBuffer[0]
        val lsb = platformBuffer[1]
        val value =
            (
                (0xff and msb.toInt() shl 8)
                    or (0xff and lsb.toInt() shl 0)
            ).toUShort()
        assertEquals(value, uShort)
        assertEquals(2, platformBuffer.remaining())
    }

    @Test
    fun allUShortValues() {
        val buffer = PlatformBuffer.allocate(UShort.MAX_VALUE.toInt() * UShort.SIZE_BYTES)
        (0 until UShort.MAX_VALUE.toInt()).forEach {
            buffer.writeUShort(it.toUShort())
        }
        buffer.resetForRead()
        (0 until UShort.MAX_VALUE.toInt()).forEach {
            assertEquals(it, buffer.readUnsignedShort().toInt())
        }
    }

    @Test
    fun relativeUnsignedShortHalf() {
        val platformBuffer = PlatformBuffer.allocate(2)
        val uShort = (UShort.MAX_VALUE / 2u).toUShort()
        platformBuffer.writeUShort(uShort)
        platformBuffer.resetForRead()
        val actual = platformBuffer.readUnsignedShort().toInt()
        assertEquals(uShort.toInt(), actual)
        assertEquals(uShort.toString(), actual.toString())
        assertEquals(0, platformBuffer.remaining())
    }

    @Test
    fun absoluteUnsignedShortHalf() {
        val platformBuffer = PlatformBuffer.allocate(UShort.SIZE_BYTES)
        val uShort = (UShort.MAX_VALUE / 2u).toUShort()
        platformBuffer[0] = uShort
        val actual = platformBuffer.getUnsignedShort(0)
        assertEquals(uShort, actual)
        assertEquals(uShort.toString(), actual.toString())
        assertEquals(0, platformBuffer.position())
    }

    @Test
    fun relativeUnsignedInt() {
        val platformBuffer = PlatformBuffer.allocate(4)
        val uInt = (-1).toUInt()
        platformBuffer.writeUInt(uInt)
        platformBuffer.resetForRead()
        assertEquals(uInt.toLong(), platformBuffer.readUnsignedInt().toLong())
        assertEquals(0, platformBuffer.remaining())
    }

    @Test
    fun absoluteUnsignedInt() {
        val platformBuffer = PlatformBuffer.allocate(4)
        val uInt = (-1).toUInt()
        platformBuffer[0] = uInt
        assertEquals(uInt.toLong(), platformBuffer.getUnsignedInt(0).toLong())
        assertEquals(4, platformBuffer.remaining())
    }

    @Test
    fun unsignedIntHalf() {
        val platformBuffer = PlatformBuffer.allocate(4)
        val uInt = Int.MAX_VALUE.toUInt() / 2u
        platformBuffer.writeUInt(uInt)
        platformBuffer.resetForRead()
        assertEquals(uInt.toLong(), platformBuffer.readUnsignedInt().toLong())
        assertEquals(0, platformBuffer.remaining())
    }

    @Test
    fun relativeLong() {
        val platformBuffer = PlatformBuffer.allocate(Long.SIZE_BYTES)
        val long = (1234).toLong()
        assertEquals(0, platformBuffer.position())
        platformBuffer.writeLong(long)
        assertEquals(Long.SIZE_BYTES, platformBuffer.position())
        platformBuffer.resetForRead()
        assertEquals(0, platformBuffer.position())
        assertEquals(long, platformBuffer.readLong())
        assertEquals(Long.SIZE_BYTES, platformBuffer.position())
        platformBuffer.resetForRead()
        assertEquals(0, platformBuffer.position())
        assertEquals(long, platformBuffer.readNumberWithByteSize(Long.SIZE_BYTES))
        assertEquals(Long.SIZE_BYTES, platformBuffer.position())

        val platformBufferLittleEndian =
            PlatformBuffer.allocate(Long.SIZE_BYTES, byteOrder = ByteOrder.LITTLE_ENDIAN)
        platformBufferLittleEndian.writeLong(long)
        platformBufferLittleEndian.resetForRead()
        assertEquals(long, platformBufferLittleEndian.readLong())
        platformBufferLittleEndian.resetForRead()
        assertEquals(long, platformBufferLittleEndian.readNumberWithByteSize(Long.SIZE_BYTES))
    }

    @Test
    fun absoluteLong() {
        val platformBuffer = PlatformBuffer.allocate(Long.SIZE_BYTES)
        val long = 12345L
        assertEquals(0, platformBuffer.position())
        platformBuffer[0] = long
        assertEquals(0, platformBuffer.position())
        assertEquals(
            long,
            platformBuffer.getLong(0),
            "getLong BIG_ENDIAN buffer[" +
                "${platformBuffer[0]}, ${platformBuffer[1]}, ${platformBuffer[2]}, ${platformBuffer[3]}, " +
                "${platformBuffer[4]}, ${platformBuffer[5]}, ${platformBuffer[6]}, ${platformBuffer[7]}]",
        )
        assertEquals(0, platformBuffer.position())
        assertEquals(
            long,
            platformBuffer.getNumberWithStartIndexAndByteSize(0, Long.SIZE_BYTES),
            "getNumberWithStartIndexAndByteSize",
        )
        assertEquals(0, platformBuffer.position())

        val platformBufferLE =
            PlatformBuffer.allocate(Long.SIZE_BYTES, byteOrder = ByteOrder.LITTLE_ENDIAN)
        platformBufferLE[0] = long
        assertEquals(0, platformBufferLE.position())
        assertEquals(
            long,
            platformBufferLE.getLong(0),
            "getLong LITTLE_ENDIAN buffer[" +
                "${platformBufferLE[0]}, ${platformBufferLE[1]}, ${platformBufferLE[2]}, ${platformBufferLE[3]}, " +
                "${platformBufferLE[4]}, ${platformBufferLE[5]}, ${platformBufferLE[6]}, ${platformBufferLE[7]}]",
        )
        assertEquals(0, platformBufferLE.position())
        assertEquals(
            long,
            platformBufferLE.getNumberWithStartIndexAndByteSize(0, Long.SIZE_BYTES),
            "getNumberWithStartIndexAndByteSizeLittleEndian",
        )
        assertEquals(0, platformBufferLE.position())
    }

    @Test
    fun relativeLongBits() {
        val platformBuffer = PlatformBuffer.allocate(Long.SIZE_BYTES)
        val long = (1234).toLong()
        platformBuffer.writeNumberOfByteSize(long, Long.SIZE_BYTES)
        platformBuffer.resetForRead()
        assertEquals(long, platformBuffer.readLong())
        platformBuffer.resetForRead()
        assertEquals(long, platformBuffer.readNumberWithByteSize(Long.SIZE_BYTES))

        val platformBufferLittleEndian =
            PlatformBuffer.allocate(Long.SIZE_BYTES, byteOrder = ByteOrder.LITTLE_ENDIAN)
        platformBufferLittleEndian.writeNumberOfByteSize(long, Long.SIZE_BYTES)
        platformBufferLittleEndian.resetForRead()
        assertEquals(long, platformBufferLittleEndian.readLong())
        platformBufferLittleEndian.resetForRead()
        assertEquals(long, platformBufferLittleEndian.readNumberWithByteSize(Long.SIZE_BYTES))
    }

    @Test
    fun absoluteLongBits() {
        val platformBuffer = PlatformBuffer.allocate(Long.SIZE_BYTES)
        val long = (1234).toLong()
        platformBuffer.setIndexNumberAndByteSize(0, long, Long.SIZE_BYTES)
        assertEquals(0, platformBuffer.position())
        assertEquals(long, platformBuffer.getLong(0))
        assertEquals(0, platformBuffer.position())
        assertEquals(long, platformBuffer.getNumberWithStartIndexAndByteSize(0, Long.SIZE_BYTES))
        assertEquals(0, platformBuffer.position())

        val platformBufferLittleEndian =
            PlatformBuffer.allocate(Long.SIZE_BYTES, byteOrder = ByteOrder.LITTLE_ENDIAN)
        platformBufferLittleEndian.setIndexNumberAndByteSize(0, long, Long.SIZE_BYTES)
        assertEquals(0, platformBufferLittleEndian.position())
        assertEquals(long, platformBufferLittleEndian.getLong(0))
        assertEquals(0, platformBufferLittleEndian.position())
        assertEquals(long, platformBufferLittleEndian.getNumberWithStartIndexAndByteSize(0, Long.SIZE_BYTES))
        assertEquals(0, platformBufferLittleEndian.position())
    }

    @Test
    fun longEdgeCases() {
        // Test edge cases that could break two-int implementations
        val testValues =
            listOf(
                Long.MIN_VALUE, // 0x8000000000000000 - sign bit set, all others 0
                Long.MAX_VALUE, // 0x7FFFFFFFFFFFFFFF - all bits except sign
                -1L, // 0xFFFFFFFFFFFFFFFF - all bits set
                0L, // all bits clear
                0x00000000FFFFFFFFL, // low 32 bits all set
                -0x100000000L, // high 32 bits all set (0xFFFFFFFF00000000)
                0x0000000100000000L, // bit 32 set (boundary between low and high int)
                0x1122334455667788L, // distinct bytes for endianness verification
                -0x1122334455667788L, // negative version
                0x7FFFFFFF00000000L, // max positive high int, zero low
                0x0000000080000000L, // zero high, min negative as low int pattern
            )

        for (value in testValues) {
            // Test big endian
            val bufferBE = PlatformBuffer.allocate(Long.SIZE_BYTES, byteOrder = ByteOrder.BIG_ENDIAN)
            bufferBE.writeLong(value)
            bufferBE.resetForRead()
            assertEquals(value, bufferBE.readLong(), "Big endian relative read/write failed for $value")

            bufferBE.position(0)
            assertEquals(value, bufferBE.getLong(0), "Big endian absolute read failed for $value")

            // Test little endian
            val bufferLE = PlatformBuffer.allocate(Long.SIZE_BYTES, byteOrder = ByteOrder.LITTLE_ENDIAN)
            bufferLE.writeLong(value)
            bufferLE.resetForRead()
            assertEquals(value, bufferLE.readLong(), "Little endian relative read/write failed for $value")

            bufferLE.position(0)
            assertEquals(value, bufferLE.getLong(0), "Little endian absolute read failed for $value")

            // Test indexed set/get
            val bufferIndexedBE = PlatformBuffer.allocate(Long.SIZE_BYTES, byteOrder = ByteOrder.BIG_ENDIAN)
            bufferIndexedBE[0] = value
            assertEquals(value, bufferIndexedBE.getLong(0), "Big endian indexed set/get failed for $value")

            val bufferIndexedLE = PlatformBuffer.allocate(Long.SIZE_BYTES, byteOrder = ByteOrder.LITTLE_ENDIAN)
            bufferIndexedLE[0] = value
            assertEquals(value, bufferIndexedLE.getLong(0), "Little endian indexed set/get failed for $value")
        }
    }

    @Test
    fun realtiveFloat() {
        val platformBuffer = PlatformBuffer.allocate(Float.SIZE_BYTES)
        val float = 123.456f
        platformBuffer.writeFloat(float)
        platformBuffer.resetForRead()
        // Note that in Kotlin/JS Float range is wider than "single format" bit layout can represent,
        // so some Float values may overflow, underflow or lose their accuracy after conversion to bits and back.
        assertTrue { (float - platformBuffer.readFloat()).absoluteValue < 0.00001f }
    }

    @Test
    fun absoluteFloat() {
        val platformBuffer = PlatformBuffer.allocate(Float.SIZE_BYTES)
        val float = 123.456f
        platformBuffer[0] = float
        assertEquals(0, platformBuffer.position())
        // Note that in Kotlin/JS Float range is wider than "single format" bit layout can represent,
        // so some Float values may overflow, underflow or lose their accuracy after conversion to bits and back.
        assertTrue { (float - platformBuffer.getFloat(0)).absoluteValue < 0.00001f }
        assertEquals(0, platformBuffer.position())
    }

    @Test
    fun relativeDouble() {
        val platformBuffer = PlatformBuffer.allocate(Double.SIZE_BYTES)
        val double = 123.456
        platformBuffer.writeDouble(double)
        platformBuffer.resetForRead()
        assertEquals(double, platformBuffer.readDouble())
    }

    @Test
    fun absoluteDouble() {
        val platformBuffer = PlatformBuffer.allocate(Double.SIZE_BYTES)
        val double = 123.456
        assertEquals(0, platformBuffer.position())
        platformBuffer[0] = double
        assertEquals(0, platformBuffer.position())
        assertEquals(
            double,
            platformBuffer.getDouble(0),
            "getDouble BIG_ENDIAN buffer[" +
                "${platformBuffer[0]}, ${platformBuffer[1]}, ${platformBuffer[2]}, ${platformBuffer[3]}, " +
                "${platformBuffer[4]}, ${platformBuffer[5]}, ${platformBuffer[6]}, ${platformBuffer[7]}]",
        )
        assertEquals(0, platformBuffer.position())

        val platformBufferLE =
            PlatformBuffer.allocate(Long.SIZE_BYTES, byteOrder = ByteOrder.LITTLE_ENDIAN)
        platformBufferLE[0] = double
        assertEquals(0, platformBufferLE.position())
        assertEquals(
            double,
            platformBufferLE.getDouble(0),
            "getDouble LITTLE_ENDIAN buffer[" +
                "${platformBufferLE[0]}, ${platformBufferLE[1]}, ${platformBufferLE[2]}, ${platformBufferLE[3]}, " +
                "${platformBufferLE[4]}, ${platformBufferLE[5]}, ${platformBufferLE[6]}, ${platformBufferLE[7]}]",
        )
        assertEquals(0, platformBufferLE.position())
    }

    @Test
    fun positionWriteBytes() {
        val text = "Hello world!"
        val input = PlatformBuffer.wrap(text.encodeToByteArray())
        val output = PlatformBuffer.allocate(text.length)
        output.write(input)
        assertEquals(input.position(), text.length)
        assertEquals(output.position(), text.length)
        output.position(0)
        assertEquals(output.readString(text.length), text)
    }

    @Test
    fun utf8String() {
        val string = "yolo swag lyfestyle"
        assertEquals(19, string.utf8Length())
        val platformBuffer = PlatformBuffer.allocate(19)
        platformBuffer.writeString(string, Charset.UTF8)
        platformBuffer.resetForRead()
        val actual = platformBuffer.readString(19, Charset.UTF8)
        assertEquals(string.length, actual.length)
        assertEquals(string, actual)
    }

    @Test
    fun readUtf8LineSingle() {
        val text = "hello"
        val buffer = text.toReadBuffer(Charset.UTF8)
        assertEquals("hello", buffer.readUtf8Line().toString())
        assertEquals(buffer.remaining(), 0)
    }

    @Test
    fun readUtf8LineDouble() {
        val text = "hello\r\n"
        val buffer = text.toReadBuffer(Charset.UTF8)
        assertEquals("hello", buffer.readUtf8Line().toString())
        assertEquals("", buffer.readUtf8Line().toString())
        assertEquals(buffer.remaining(), 0)
    }

    @Test
    fun readUtf8LineStart() {
        val text = "\r\nhello"
        val buffer = text.toReadBuffer(Charset.UTF8)
        assertEquals("", buffer.readUtf8Line().toString())
        assertEquals("hello", buffer.readUtf8Line().toString())
        assertEquals(buffer.remaining(), 0)
    }

    @Test
    fun readUtf8LineStartN() {
        val text = "\nhello"
        val buffer = text.toReadBuffer(Charset.UTF8)
        assertEquals("", buffer.readUtf8Line().toString())
        assertEquals("hello", buffer.readUtf8Line().toString())
        assertEquals(buffer.remaining(), 0)
    }

    @Test
    fun readUtf8LineMix() {
        val text = "\nhello\r\nhello\nhello\r\n"
        val buffer = text.toReadBuffer(Charset.UTF8)
        assertEquals("", buffer.readUtf8Line().toString())
        assertEquals("hello", buffer.readUtf8Line().toString())
        assertEquals("hello", buffer.readUtf8Line().toString())
        assertEquals("hello", buffer.readUtf8Line().toString())
        assertEquals("", buffer.readUtf8Line().toString())
        assertEquals(buffer.remaining(), 0)
    }

    @Test
    fun readUtf8LineMixMulti() {
        val text = "\nhello\r\n\nhello\n\nhello\r\n"
        val buffer = text.toReadBuffer(Charset.UTF8)
        assertEquals("", buffer.readUtf8Line().toString())
        assertEquals("hello", buffer.readUtf8Line().toString())
        assertEquals("", buffer.readUtf8Line().toString())
        assertEquals("hello", buffer.readUtf8Line().toString())
        assertEquals("", buffer.readUtf8Line().toString())
        assertEquals("hello", buffer.readUtf8Line().toString())
        assertEquals("", buffer.readUtf8Line().toString())
        assertEquals(buffer.remaining(), 0)
    }

    @Test
    fun readUtf8Line() {
        val stringArray = "yolo swag lyfestyle".split(' ')
        assertEquals(3, stringArray.size)
        val newLineString = stringArray.joinToString("\r\n")
        val stringBuffer = newLineString.toReadBuffer(Charset.UTF8)
        stringArray.forEach {
            val line = stringBuffer.readUtf8Line()
            assertEquals(it, line.toString())
        }
    }

    @Test
    fun readByteArray() {
        val string = "yolo swag lyfestyle"
        val stringBuffer = string.toReadBuffer(Charset.UTF8)
        assertEquals(string[0], Char(stringBuffer.readByte().toInt()))
        val s = stringBuffer.readByteArray(stringBuffer.remaining())
        assertEquals(string.substring(1), s.decodeToString())
    }

    @Test
    fun readByteArraySizeZeroDoesNotCrash() {
        val string = "yolo swag lyfestyle"
        val stringBuffer = string.toReadBuffer(Charset.UTF8)
        val emptyByteArray = stringBuffer.readByteArray(0)
        assertContentEquals(emptyByteArray, ByteArray(0))
    }

    @Test
    fun endianWrite() {
        val littleEndian2 = PlatformBuffer.allocate(2, byteOrder = ByteOrder.LITTLE_ENDIAN)
        littleEndian2.writeShort(0x0102.toShort())
        littleEndian2.resetForRead()
        assertEquals(0x02u, littleEndian2.readUnsignedByte())
        assertEquals(0x01u, littleEndian2.readUnsignedByte())

        val bigEndian2 = PlatformBuffer.allocate(2, byteOrder = ByteOrder.BIG_ENDIAN)
        bigEndian2.writeShort(0x0102.toShort())
        bigEndian2.resetForRead()
        assertEquals(0x01u, bigEndian2.readUnsignedByte())
        assertEquals(0x02u, bigEndian2.readUnsignedByte())

        val littleEndian4 = PlatformBuffer.allocate(4, byteOrder = ByteOrder.LITTLE_ENDIAN)
        littleEndian4.writeInt(0x01020304)
        littleEndian4.resetForRead()
        assertEquals(0x04u, littleEndian4.readUnsignedByte())
        assertEquals(0x03u, littleEndian4.readUnsignedByte())
        assertEquals(0x02u, littleEndian4.readUnsignedByte())
        assertEquals(0x01u, littleEndian4.readUnsignedByte())

        val bigEndian4 = PlatformBuffer.allocate(4, byteOrder = ByteOrder.BIG_ENDIAN)
        bigEndian4.writeInt(0x01020304)
        bigEndian4.resetForRead()
        assertEquals(0x01u, bigEndian4.readUnsignedByte())
        assertEquals(0x02u, bigEndian4.readUnsignedByte())
        assertEquals(0x03u, bigEndian4.readUnsignedByte())
        assertEquals(0x04u, bigEndian4.readUnsignedByte())

        val littleEndian8 = PlatformBuffer.allocate(8, byteOrder = ByteOrder.LITTLE_ENDIAN)
        littleEndian8.writeLong(0x0102030405060708)
        littleEndian8.resetForRead()
        assertEquals(0x08u, littleEndian8.readUnsignedByte())
        assertEquals(0x07u, littleEndian8.readUnsignedByte())
        assertEquals(0x06u, littleEndian8.readUnsignedByte())
        assertEquals(0x05u, littleEndian8.readUnsignedByte())
        assertEquals(0x04u, littleEndian8.readUnsignedByte())
        assertEquals(0x03u, littleEndian8.readUnsignedByte())
        assertEquals(0x02u, littleEndian8.readUnsignedByte())
        assertEquals(0x01u, littleEndian8.readUnsignedByte())

        val bigEndian8 = PlatformBuffer.allocate(8, byteOrder = ByteOrder.BIG_ENDIAN)
        bigEndian8.writeLong(0x0102030405060708)
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
        val littleEndian2 = PlatformBuffer.allocate(2, byteOrder = ByteOrder.LITTLE_ENDIAN)
        littleEndian2.writeByte(0x01.toByte())
        littleEndian2.writeByte(0x02.toByte())
        littleEndian2.resetForRead()
        assertEquals(0x0201.toShort(), littleEndian2.readShort())

        val bigEndian2 = PlatformBuffer.allocate(2, byteOrder = ByteOrder.BIG_ENDIAN)
        bigEndian2.writeByte(0x01.toByte())
        bigEndian2.writeByte(0x02.toByte())
        bigEndian2.resetForRead()
        assertEquals(0x0102.toShort(), bigEndian2.readShort())

        val littleEndian4 = PlatformBuffer.allocate(4, byteOrder = ByteOrder.LITTLE_ENDIAN)
        littleEndian4.writeByte(0x01.toByte())
        littleEndian4.writeByte(0x02.toByte())
        littleEndian4.writeByte(0x03.toByte())
        littleEndian4.writeByte(0x04.toByte())
        littleEndian4.resetForRead()
        assertEquals(0x04030201, littleEndian4.readInt())

        val bigEndian4 = PlatformBuffer.allocate(4, byteOrder = ByteOrder.BIG_ENDIAN)
        bigEndian4.writeByte(0x01.toByte())
        bigEndian4.writeByte(0x02.toByte())
        bigEndian4.writeByte(0x03.toByte())
        bigEndian4.writeByte(0x04.toByte())
        bigEndian4.resetForRead()
        assertEquals(0x01020304, bigEndian4.readInt())

        val littleEndian8 = PlatformBuffer.allocate(8, byteOrder = ByteOrder.LITTLE_ENDIAN)
        littleEndian8.writeByte(0x01.toByte())
        littleEndian8.writeByte(0x02.toByte())
        littleEndian8.writeByte(0x03.toByte())
        littleEndian8.writeByte(0x04.toByte())
        littleEndian8.writeByte(0x05.toByte())
        littleEndian8.writeByte(0x06.toByte())
        littleEndian8.writeByte(0x07.toByte())
        littleEndian8.writeByte(0x08.toByte())
        littleEndian8.resetForRead()
        assertEquals(0x0807060504030201, littleEndian8.readLong())

        val bigEndian8 = PlatformBuffer.allocate(8, byteOrder = ByteOrder.BIG_ENDIAN)
        bigEndian8.writeByte(0x01.toByte())
        bigEndian8.writeByte(0x02.toByte())
        bigEndian8.writeByte(0x03.toByte())
        bigEndian8.writeByte(0x04.toByte())
        bigEndian8.writeByte(0x05.toByte())
        bigEndian8.writeByte(0x06.toByte())
        bigEndian8.writeByte(0x07.toByte())
        bigEndian8.writeByte(0x08.toByte())
        bigEndian8.resetForRead()
        assertEquals(0x0102030405060708, bigEndian8.readLong())
    }

    @Test
    fun partialByteArray() {
        val byteArray = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val partialArray = byteArray.sliceArray(2..6)
        val buffer = PlatformBuffer.allocate(byteArray.size)
        val position = buffer.position()
        buffer.writeBytes(byteArray, 2, 5)
        val deltaPosition = buffer.position() - position
        assertEquals(5, deltaPosition)
        buffer.resetForRead()
        assertContentEquals(partialArray, buffer.readByteArray(5))
    }

    @Test
    fun wrap() {
        val byteArray = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val buffer = PlatformBuffer.wrap(byteArray)
        assertEquals(byteArray.size, buffer.remaining(), "remaining")
        assertContentEquals(byteArray, buffer.readByteArray(buffer.remaining()), "equals")
        buffer.resetForRead()
        assertEquals(byteArray.size, buffer.remaining(), "remaining")
        byteArray.fill(-1)
        val modified = byteArray.copyOf()
        assertContentEquals(buffer.readByteArray(buffer.remaining()), modified, "modify original")
    }

    @Test
    fun encoding() {
        val string = "yolo swag lyfestyle"
        var successfulCount = 0
        Charset.values().forEach {
            val stringBuffer = PlatformBuffer.allocate(80)
            try {
                stringBuffer.writeString(string, it)
                stringBuffer.resetForRead()
                assertEquals(
                    string,
                    stringBuffer.readString(stringBuffer.remaining(), it),
                    it.toString(),
                )
                successfulCount++
            } catch (e: UnsupportedOperationException) {
                // unallowed type.
            }
        }
        assertTrue { successfulCount > 0 }
    }

    @Test
    fun invalidCharacterInBufferThrows() {
        assertFails {
            val buffer = PlatformBuffer.wrap(byteArrayOf(2, 126, 33, -66, -100, 4, -39, 108))
            buffer.readString(buffer.remaining())
            println("should have failed by now")
        }
    }

    @Test
    fun emptyString() {
        val s = "".toReadBuffer()
        assertEquals(0, s.position())
        assertEquals(0, s.limit())
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun complexReadWrapReadAgain1() {
        val str = "0001A960DBD8A500006500006400010A003132333435363738"

        @OptIn(ExperimentalStdlibApi::class)
        fun String.toByteArrayFromHex(): ByteArray {
            var value = this
            if (value.isEmpty()) return byteArrayOf()
            val length = ceil(value.length / 2.0).toInt()
            value = value.padStart(length * 2, '0')
            return value.hexToByteArray()
        }
        val hex = str.toByteArrayFromHex()
        assertContentEquals(
            hex,
            byteArrayOf(0, 1, -87, 96, -37, -40, -91, 0, 0, 101, 0, 0, 100, 0, 1, 10, 0, 49, 50, 51, 52, 53, 54, 55, 56),
        )
        val buf = PlatformBuffer.wrap(hex)
        assertBufferEquals(
            buf,
            byteArrayOf(0, 1, -87, 96, -37, -40, -91, 0, 0, 101, 0, 0, 100, 0, 1, 10, 0, 49, 50, 51, 52, 53, 54, 55, 56),
        )
        assertEquals(0, buf.position())
        assertEquals(25, buf.limit())
        val messageId = buf.readUnsignedShort()
        assertBufferEquals(buf, byteArrayOf(-87, 96, -37, -40, -91, 0, 0, 101, 0, 0, 100, 0, 1, 10, 0, 49, 50, 51, 52, 53, 54, 55, 56))
        assertEquals(messageId.toInt(), 1)
        assertEquals(2, buf.position())
        assertEquals(25, buf.limit())
        val cmd = buf.readByte()
        assertBufferEquals(buf, byteArrayOf(96, -37, -40, -91, 0, 0, 101, 0, 0, 100, 0, 1, 10, 0, 49, 50, 51, 52, 53, 54, 55, 56))
        assertEquals(-87, cmd)
        assertEquals(3, buf.position())
        assertEquals(25, buf.limit())
        val rem = buf.remaining()
        assertEquals(22, rem)
        val data = buf.readByteArray(rem)
        assertContentEquals(data, byteArrayOf(96, -37, -40, -91, 0, 0, 101, 0, 0, 100, 0, 1, 10, 0, 49, 50, 51, 52, 53, 54, 55, 56))
        val newBuffer = PlatformBuffer.wrap(data)
        assertEquals(0, newBuffer.position())
        assertEquals(22, newBuffer.limit())
        assertBufferEquals(newBuffer, byteArrayOf(96, -37, -40, -91, 0, 0, 101, 0, 0, 100, 0, 1, 10, 0, 49, 50, 51, 52, 53, 54, 55, 56))
        val b = newBuffer.readByteArray(4)
        assertEquals(b.toHexString().uppercase(), "60DBD8A5")
    }

    fun assertBufferEquals(
        b: ReadBuffer,
        byteArray: ByteArray,
    ) {
        val p = b.position()
        val l = b.limit()
        assertContentEquals(b.readByteArray(b.remaining()), byteArray)
        b.position(p)
        b.setLimit(l)
    }

    @Test
    fun wrapByteArraySharesBuffer() {
        val array = byteArrayOf(0, 1, 2, 3, 4, 5)
        val buf = PlatformBuffer.wrap(array)
        assertEquals(1, buf[1])
        array[1] = -1
        assertEquals(-1, buf[1])
    }

    @Test
    fun simpleReadWrapReadAgain() {
        val array = byteArrayOf(0, 1, 2, 3, 4, 5)
        val buf = PlatformBuffer.wrap(array)
        buf.readBytes(3)
        val bytesRead = buf.readByteArray(buf.remaining())
        assertEquals(buf.position(), 6)
        assertEquals(buf.limit(), 6)
        assertEquals(bytesRead.size, 3)
        val buffer2 = PlatformBuffer.wrap(bytesRead)
        assertBufferEquals(buffer2, byteArrayOf(3, 4, 5))
    }
}
