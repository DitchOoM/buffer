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
        val input = text.toReadBuffer()
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
    fun readLineSingle() {
        val text = "hello"
        val buffer = text.toReadBuffer(Charset.UTF8)
        assertEquals("hello", buffer.readLine().toString())
        assertEquals(buffer.remaining(), 0)
    }

    @Test
    fun readLineDouble() {
        val text = "hello\r\n"
        val buffer = text.toReadBuffer(Charset.UTF8)
        assertEquals("hello", buffer.readLine().toString())
        assertEquals("", buffer.readLine().toString())
        assertEquals(buffer.remaining(), 0)
    }

    @Test
    fun readLineStart() {
        val text = "\r\nhello"
        val buffer = text.toReadBuffer(Charset.UTF8)
        assertEquals("", buffer.readLine().toString())
        assertEquals("hello", buffer.readLine().toString())
        assertEquals(buffer.remaining(), 0)
    }

    @Test
    fun readLineStartN() {
        val text = "\nhello"
        val buffer = text.toReadBuffer(Charset.UTF8)
        assertEquals("", buffer.readLine().toString())
        assertEquals("hello", buffer.readLine().toString())
        assertEquals(buffer.remaining(), 0)
    }

    @Test
    fun readLineMix() {
        val text = "\nhello\r\nhello\nhello\r\n"
        val buffer = text.toReadBuffer(Charset.UTF8)
        assertEquals("", buffer.readLine().toString())
        assertEquals("hello", buffer.readLine().toString())
        assertEquals("hello", buffer.readLine().toString())
        assertEquals("hello", buffer.readLine().toString())
        assertEquals("", buffer.readLine().toString())
        assertEquals(buffer.remaining(), 0)
    }

    @Test
    fun readLineMixMulti() {
        val text = "\nhello\r\n\nhello\n\nhello\r\n"
        val buffer = text.toReadBuffer(Charset.UTF8)
        assertEquals("", buffer.readLine().toString())
        assertEquals("hello", buffer.readLine().toString())
        assertEquals("", buffer.readLine().toString())
        assertEquals("hello", buffer.readLine().toString())
        assertEquals("", buffer.readLine().toString())
        assertEquals("hello", buffer.readLine().toString())
        assertEquals("", buffer.readLine().toString())
        assertEquals(buffer.remaining(), 0)
    }

    @Test
    fun readLine() {
        val stringArray = "yolo swag lyfestyle".split(' ')
        assertEquals(3, stringArray.size)
        val newLineString = stringArray.joinToString("\r\n")
        val stringBuffer = newLineString.toReadBuffer(Charset.UTF8)
        stringArray.forEach {
            val line = stringBuffer.readLine()
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

    // region contentEquals tests

    @Test
    fun contentEqualsIdenticalBuffers() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val buf1 = PlatformBuffer.wrap(bytes.copyOf())
        val buf2 = PlatformBuffer.wrap(bytes.copyOf())
        assertTrue(buf1.contentEquals(buf2))
    }

    @Test
    fun contentEqualsDifferentBuffers() {
        val buf1 = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        val buf2 = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 6))
        assertFalse(buf1.contentEquals(buf2))
    }

    @Test
    fun contentEqualsDifferentSizes() {
        val buf1 = PlatformBuffer.wrap(byteArrayOf(1, 2, 3))
        val buf2 = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4))
        assertFalse(buf1.contentEquals(buf2))
    }

    @Test
    fun contentEqualsEmptyBuffers() {
        val buf1 = PlatformBuffer.allocate(0)
        val buf2 = PlatformBuffer.allocate(0)
        assertTrue(buf1.contentEquals(buf2))
    }

    @Test
    fun contentEqualsWithPosition() {
        val buf1 = PlatformBuffer.wrap(byteArrayOf(0, 1, 2, 3, 4))
        val buf2 = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4))
        buf1.position(1)
        assertTrue(buf1.contentEquals(buf2))
    }

    @Test
    fun contentEqualsLargeBuffer() {
        // Test buffer larger than 8 bytes to exercise bulk comparison
        val size = 100
        val bytes = ByteArray(size) { it.toByte() }
        val buf1 = PlatformBuffer.wrap(bytes.copyOf())
        val buf2 = PlatformBuffer.wrap(bytes.copyOf())
        assertTrue(buf1.contentEquals(buf2))
    }

    @Test
    fun contentEqualsLargeBufferDifferAtEnd() {
        val size = 100
        val bytes1 = ByteArray(size) { it.toByte() }
        val bytes2 = bytes1.copyOf()
        bytes2[size - 1] = -1 // Differ at end
        val buf1 = PlatformBuffer.wrap(bytes1)
        val buf2 = PlatformBuffer.wrap(bytes2)
        assertFalse(buf1.contentEquals(buf2))
    }

    // endregion

    // region mismatch tests

    @Test
    fun mismatchIdenticalBuffers() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val buf1 = PlatformBuffer.wrap(bytes.copyOf())
        val buf2 = PlatformBuffer.wrap(bytes.copyOf())
        assertEquals(-1, buf1.mismatch(buf2))
    }

    @Test
    fun mismatchAtStart() {
        val buf1 = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        val buf2 = PlatformBuffer.wrap(byteArrayOf(0, 2, 3, 4, 5))
        assertEquals(0, buf1.mismatch(buf2))
    }

    @Test
    fun mismatchAtMiddle() {
        val buf1 = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        val buf2 = PlatformBuffer.wrap(byteArrayOf(1, 2, 9, 4, 5))
        assertEquals(2, buf1.mismatch(buf2))
    }

    @Test
    fun mismatchAtEnd() {
        val buf1 = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        val buf2 = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 6))
        assertEquals(4, buf1.mismatch(buf2))
    }

    @Test
    fun mismatchDifferentSizesMatchPrefix() {
        val buf1 = PlatformBuffer.wrap(byteArrayOf(1, 2, 3))
        val buf2 = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(3, buf1.mismatch(buf2)) // Returns minLength when prefix matches but sizes differ
    }

    @Test
    fun mismatchDifferentSizesMismatchPrefix() {
        val buf1 = PlatformBuffer.wrap(byteArrayOf(1, 2, 9))
        val buf2 = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(2, buf1.mismatch(buf2))
    }

    @Test
    fun mismatchEmptyBuffers() {
        val buf1 = PlatformBuffer.allocate(0)
        val buf2 = PlatformBuffer.allocate(0)
        assertEquals(-1, buf1.mismatch(buf2))
    }

    @Test
    fun mismatchEmptyVsNonEmpty() {
        val buf1 = PlatformBuffer.allocate(0)
        val buf2 = PlatformBuffer.wrap(byteArrayOf(1, 2, 3))
        assertEquals(0, buf1.mismatch(buf2))
    }

    @Test
    fun mismatchLargeBuffer() {
        // Test buffer larger than 8 bytes to exercise bulk comparison
        val size = 100
        val bytes1 = ByteArray(size) { it.toByte() }
        val bytes2 = bytes1.copyOf()
        bytes2[50] = -1 // Differ at index 50
        val buf1 = PlatformBuffer.wrap(bytes1)
        val buf2 = PlatformBuffer.wrap(bytes2)
        assertEquals(50, buf1.mismatch(buf2))
    }

    @Test
    fun mismatchWithPosition() {
        val buf1 = PlatformBuffer.wrap(byteArrayOf(0, 1, 2, 3, 4))
        val buf2 = PlatformBuffer.wrap(byteArrayOf(1, 2, 9, 4))
        buf1.position(1)
        assertEquals(2, buf1.mismatch(buf2)) // Differ at relative index 2
    }

    // endregion

    // region indexOf(byte) tests

    @Test
    fun indexOfByteFound() {
        val buf = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(2, buf.indexOf(3.toByte()))
    }

    @Test
    fun indexOfByteNotFound() {
        val buf = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(-1, buf.indexOf(9.toByte()))
    }

    @Test
    fun indexOfByteAtStart() {
        val buf = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(0, buf.indexOf(1.toByte()))
    }

    @Test
    fun indexOfByteAtEnd() {
        val buf = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(4, buf.indexOf(5.toByte()))
    }

    @Test
    fun indexOfByteEmptyBuffer() {
        val buf = PlatformBuffer.allocate(0)
        assertEquals(-1, buf.indexOf(1.toByte()))
    }

    @Test
    fun indexOfByteWithPosition() {
        val buf = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 1, 5))
        buf.position(2)
        assertEquals(1, buf.indexOf(1.toByte())) // Returns index relative to position
    }

    @Test
    fun indexOfByteDuplicates() {
        val buf = PlatformBuffer.wrap(byteArrayOf(1, 2, 1, 2, 1))
        assertEquals(0, buf.indexOf(1.toByte())) // Returns first occurrence
    }

    @Test
    fun indexOfByteNegative() {
        val buf = PlatformBuffer.wrap(byteArrayOf(-1, -2, -3, -4, -5))
        assertEquals(2, buf.indexOf((-3).toByte()))
    }

    @Test
    fun indexOfByteLargeBuffer() {
        // Test buffer larger than 8 bytes to exercise bulk search
        val size = 100
        val bytes = ByteArray(size) { 0 }
        bytes[75] = 42
        val buf = PlatformBuffer.wrap(bytes)
        assertEquals(75, buf.indexOf(42.toByte()))
    }

    @Test
    fun indexOfByteLargeBufferNotFound() {
        val size = 100
        val bytes = ByteArray(size) { 0 }
        val buf = PlatformBuffer.wrap(bytes)
        assertEquals(-1, buf.indexOf(42.toByte()))
    }

    // endregion

    // region indexOf(ReadBuffer) tests

    @Test
    fun indexOfBufferFound() {
        val buf = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        val needle = PlatformBuffer.wrap(byteArrayOf(3, 4))
        assertEquals(2, buf.indexOf(needle))
    }

    @Test
    fun indexOfBufferNotFound() {
        val buf = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        val needle = PlatformBuffer.wrap(byteArrayOf(3, 5))
        assertEquals(-1, buf.indexOf(needle))
    }

    @Test
    fun indexOfBufferAtStart() {
        val buf = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        val needle = PlatformBuffer.wrap(byteArrayOf(1, 2))
        assertEquals(0, buf.indexOf(needle))
    }

    @Test
    fun indexOfBufferAtEnd() {
        val buf = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        val needle = PlatformBuffer.wrap(byteArrayOf(4, 5))
        assertEquals(3, buf.indexOf(needle))
    }

    @Test
    fun indexOfBufferEmptyNeedle() {
        val buf = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        val needle = PlatformBuffer.allocate(0)
        assertEquals(0, buf.indexOf(needle)) // Empty needle matches at start
    }

    @Test
    fun indexOfBufferEmptyHaystack() {
        val buf = PlatformBuffer.allocate(0)
        val needle = PlatformBuffer.wrap(byteArrayOf(1, 2))
        assertEquals(-1, buf.indexOf(needle))
    }

    @Test
    fun indexOfBufferNeedleLargerThanHaystack() {
        val buf = PlatformBuffer.wrap(byteArrayOf(1, 2))
        val needle = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(-1, buf.indexOf(needle))
    }

    @Test
    fun indexOfBufferSameSize() {
        val buf = PlatformBuffer.wrap(byteArrayOf(1, 2, 3))
        val needle = PlatformBuffer.wrap(byteArrayOf(1, 2, 3))
        assertEquals(0, buf.indexOf(needle))
    }

    @Test
    fun indexOfBufferSameSizeNoMatch() {
        val buf = PlatformBuffer.wrap(byteArrayOf(1, 2, 3))
        val needle = PlatformBuffer.wrap(byteArrayOf(1, 2, 4))
        assertEquals(-1, buf.indexOf(needle))
    }

    @Test
    fun indexOfBufferMultipleMatches() {
        val buf = PlatformBuffer.wrap(byteArrayOf(1, 2, 1, 2, 1, 2))
        val needle = PlatformBuffer.wrap(byteArrayOf(1, 2))
        assertEquals(0, buf.indexOf(needle)) // Returns first match
    }

    @Test
    fun indexOfBufferSingleByte() {
        val buf = PlatformBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        val needle = PlatformBuffer.wrap(byteArrayOf(3))
        assertEquals(2, buf.indexOf(needle))
    }

    @Test
    fun indexOfBufferWithPosition() {
        val buf = PlatformBuffer.wrap(byteArrayOf(0, 1, 2, 3, 4, 5))
        val needle = PlatformBuffer.wrap(byteArrayOf(2, 3))
        buf.position(1)
        assertEquals(1, buf.indexOf(needle)) // Index 2 in haystack, but 1 relative to position 1
    }

    // endregion

    // region indexOf Short/Int/Long/String tests

    @Test
    fun indexOfShortFound() {
        val buf = PlatformBuffer.allocate(10, byteOrder = ByteOrder.BIG_ENDIAN)
        buf.writeShort(0x0102)
        buf.writeShort(0x0304)
        buf.writeShort(0x0506)
        buf.resetForRead()
        assertEquals(2, buf.indexOf(0x0304.toShort()))
    }

    @Test
    fun indexOfShortNotFound() {
        val buf = PlatformBuffer.allocate(6, byteOrder = ByteOrder.BIG_ENDIAN)
        buf.writeShort(0x0102)
        buf.writeShort(0x0304)
        buf.writeShort(0x0506)
        buf.resetForRead()
        assertEquals(-1, buf.indexOf(0x0708.toShort()))
    }

    @Test
    fun indexOfShortAtStart() {
        val buf = PlatformBuffer.allocate(6, byteOrder = ByteOrder.BIG_ENDIAN)
        buf.writeShort(0x0102)
        buf.writeShort(0x0304)
        buf.writeShort(0x0506)
        buf.resetForRead()
        assertEquals(0, buf.indexOf(0x0102.toShort()))
    }

    @Test
    fun indexOfShortUnaligned() {
        // Test finding short value at odd offset
        val buf = PlatformBuffer.allocate(7, byteOrder = ByteOrder.BIG_ENDIAN)
        buf.writeByte(0x00)
        buf.writeShort(0x0102)
        buf.writeShort(0x0304)
        buf.writeShort(0x0506)
        buf.resetForRead()
        assertEquals(1, buf.indexOf(0x0102.toShort()))
    }

    @Test
    fun indexOfIntFound() {
        val buf = PlatformBuffer.allocate(12, byteOrder = ByteOrder.BIG_ENDIAN)
        buf.writeInt(0x01020304)
        buf.writeInt(0x05060708)
        buf.writeInt(0x090A0B0C)
        buf.resetForRead()
        assertEquals(4, buf.indexOf(0x05060708))
    }

    @Test
    fun indexOfIntNotFound() {
        val buf = PlatformBuffer.allocate(12, byteOrder = ByteOrder.BIG_ENDIAN)
        buf.writeInt(0x01020304)
        buf.writeInt(0x05060708)
        buf.writeInt(0x090A0B0C)
        buf.resetForRead()
        assertEquals(-1, buf.indexOf(0x11223344))
    }

    @Test
    fun indexOfIntAtStart() {
        val buf = PlatformBuffer.allocate(12, byteOrder = ByteOrder.BIG_ENDIAN)
        buf.writeInt(0x01020304)
        buf.writeInt(0x05060708)
        buf.writeInt(0x090A0B0C)
        buf.resetForRead()
        assertEquals(0, buf.indexOf(0x01020304))
    }

    @Test
    fun indexOfLongFound() {
        val buf = PlatformBuffer.allocate(24, byteOrder = ByteOrder.BIG_ENDIAN)
        buf.writeLong(0x0102030405060708L)
        buf.writeLong(0x090A0B0C0D0E0F10L)
        buf.writeLong(0x1112131415161718L)
        buf.resetForRead()
        assertEquals(8, buf.indexOf(0x090A0B0C0D0E0F10L))
    }

    @Test
    fun indexOfLongNotFound() {
        val buf = PlatformBuffer.allocate(24, byteOrder = ByteOrder.BIG_ENDIAN)
        buf.writeLong(0x0102030405060708L)
        buf.writeLong(0x090A0B0C0D0E0F10L)
        buf.writeLong(0x1112131415161718L)
        buf.resetForRead()
        assertEquals(-1, buf.indexOf(0x2122232425262728L))
    }

    @Test
    fun indexOfLongAtStart() {
        val buf = PlatformBuffer.allocate(24, byteOrder = ByteOrder.BIG_ENDIAN)
        buf.writeLong(0x0102030405060708L)
        buf.writeLong(0x090A0B0C0D0E0F10L)
        buf.writeLong(0x1112131415161718L)
        buf.resetForRead()
        assertEquals(0, buf.indexOf(0x0102030405060708L))
    }

    @Test
    fun indexOfStringFound() {
        val buf = PlatformBuffer.allocate(100)
        buf.writeString("Hello, World!")
        buf.resetForRead()
        assertEquals(7, buf.indexOf("World"))
    }

    @Test
    fun indexOfStringNotFound() {
        val buf = PlatformBuffer.allocate(100)
        buf.writeString("Hello, World!")
        buf.resetForRead()
        assertEquals(-1, buf.indexOf("Goodbye"))
    }

    @Test
    fun indexOfStringAtStart() {
        val buf = PlatformBuffer.allocate(100)
        buf.writeString("Hello, World!")
        buf.resetForRead()
        assertEquals(0, buf.indexOf("Hello"))
    }

    @Test
    fun indexOfStringEmpty() {
        val buf = PlatformBuffer.allocate(100)
        buf.writeString("Hello, World!")
        buf.resetForRead()
        assertEquals(0, buf.indexOf(""))
    }

    @Test
    fun indexOfStringUnicode() {
        val buf = PlatformBuffer.allocate(100)
        buf.writeString("Hello, 世界!")
        buf.resetForRead()
        assertEquals(7, buf.indexOf("世界"))
    }

    // endregion

    // region fill tests

    @Test
    fun fillByte() {
        val buf = PlatformBuffer.allocate(10)
        buf.fill(0x42.toByte())
        buf.resetForRead()
        assertEquals(10, buf.remaining())
        for (i in 0 until 10) {
            assertEquals(0x42.toByte(), buf.readByte())
        }
    }

    @Test
    fun fillBytePartial() {
        val buf = PlatformBuffer.allocate(10)
        buf.writeByte(0x01)
        buf.writeByte(0x02)
        buf.fill(0x42.toByte())
        buf.resetForRead()
        assertEquals(0x01.toByte(), buf.readByte())
        assertEquals(0x02.toByte(), buf.readByte())
        for (i in 0 until 8) {
            assertEquals(0x42.toByte(), buf.readByte())
        }
    }

    @Test
    fun fillByteZero() {
        val buf = PlatformBuffer.allocate(10)
        buf.fill(0x00.toByte())
        buf.resetForRead()
        for (i in 0 until 10) {
            assertEquals(0x00.toByte(), buf.readByte())
        }
    }

    @Test
    fun fillShort() {
        val buf = PlatformBuffer.allocate(10, byteOrder = ByteOrder.BIG_ENDIAN)
        buf.fill(0x1234.toShort())
        buf.resetForRead()
        assertEquals(5, buf.remaining() / 2)
        for (i in 0 until 5) {
            assertEquals(0x1234.toShort(), buf.readShort())
        }
    }

    @Test
    fun fillInt() {
        val buf = PlatformBuffer.allocate(12, byteOrder = ByteOrder.BIG_ENDIAN)
        buf.fill(0x12345678)
        buf.resetForRead()
        assertEquals(3, buf.remaining() / 4)
        for (i in 0 until 3) {
            assertEquals(0x12345678, buf.readInt())
        }
    }

    @Test
    fun fillLong() {
        val buf = PlatformBuffer.allocate(24, byteOrder = ByteOrder.BIG_ENDIAN)
        buf.fill(0x123456789ABCDEF0L)
        buf.resetForRead()
        assertEquals(3, buf.remaining() / 8)
        for (i in 0 until 3) {
            assertEquals(0x123456789ABCDEF0L, buf.readLong())
        }
    }

    @Test
    fun fillLittleEndian() {
        val buf = PlatformBuffer.allocate(8, byteOrder = ByteOrder.LITTLE_ENDIAN)
        buf.fill(0x12345678)
        buf.resetForRead()
        // In little endian, 0x12345678 is stored as 78 56 34 12
        assertEquals(0x78.toByte(), buf.readByte())
        assertEquals(0x56.toByte(), buf.readByte())
        assertEquals(0x34.toByte(), buf.readByte())
        assertEquals(0x12.toByte(), buf.readByte())
    }

    // endregion

    // region Buffer-to-buffer copy tests (Heap/Direct interactions)

    @Test
    fun copyHeapToHeap() {
        val source = PlatformBuffer.allocate(100, AllocationZone.Heap)
        source.writeInt(0x12345678)
        source.writeString("Hello")
        source.resetForRead()

        val dest = PlatformBuffer.allocate(100, AllocationZone.Heap)
        dest.write(source)
        dest.resetForRead()

        assertEquals(0x12345678, dest.readInt())
        assertEquals("Hello", dest.readString(5))
    }

    @Test
    fun copyDirectToDirect() {
        val source = PlatformBuffer.allocate(100, AllocationZone.Direct)
        source.writeInt(0x12345678)
        source.writeString("Hello")
        source.resetForRead()

        val dest = PlatformBuffer.allocate(100, AllocationZone.Direct)
        dest.write(source)
        dest.resetForRead()

        assertEquals(0x12345678, dest.readInt())
        assertEquals("Hello", dest.readString(5))
    }

    @Test
    fun copyHeapToDirect() {
        val source = PlatformBuffer.allocate(100, AllocationZone.Heap)
        source.writeInt(0xDEADBEEF.toInt())
        source.writeLong(0x123456789ABCDEF0L)
        source.writeString("Test")
        source.resetForRead()

        val dest = PlatformBuffer.allocate(100, AllocationZone.Direct)
        dest.write(source)
        dest.resetForRead()

        assertEquals(0xDEADBEEF.toInt(), dest.readInt())
        assertEquals(0x123456789ABCDEF0L, dest.readLong())
        assertEquals("Test", dest.readString(4))
    }

    @Test
    fun copyDirectToHeap() {
        val source = PlatformBuffer.allocate(100, AllocationZone.Direct)
        source.writeInt(0xCAFEBABE.toInt())
        source.writeLong(0xFEDCBA9876543210UL.toLong())
        source.writeString("Copy")
        source.resetForRead()

        val dest = PlatformBuffer.allocate(100, AllocationZone.Heap)
        dest.write(source)
        dest.resetForRead()

        assertEquals(0xCAFEBABE.toInt(), dest.readInt())
        assertEquals(0xFEDCBA9876543210UL.toLong(), dest.readLong())
        assertEquals("Copy", dest.readString(4))
    }

    @Test
    fun copyPartialBuffer() {
        val source = PlatformBuffer.allocate(100, AllocationZone.Direct)
        source.writeInt(1)
        source.writeInt(2)
        source.writeInt(3)
        source.writeInt(4)
        source.resetForRead()
        source.readInt() // Skip first int
        // Now position=4, remaining=12 (3 ints)

        val dest = PlatformBuffer.allocate(100, AllocationZone.Heap)
        dest.write(source)
        dest.resetForRead()

        assertEquals(2, dest.readInt())
        assertEquals(3, dest.readInt())
        assertEquals(4, dest.readInt())
    }

    @Test
    fun copyLargeBuffer() {
        val size = 64 * 1024 // 64KB
        val source = PlatformBuffer.allocate(size, AllocationZone.Direct)
        // Fill with pattern
        for (i in 0 until size / 4) {
            source.writeInt(i)
        }
        source.resetForRead()

        val dest = PlatformBuffer.allocate(size, AllocationZone.Heap)
        dest.write(source)
        dest.resetForRead()

        // Verify pattern
        for (i in 0 until size / 4) {
            assertEquals(i, dest.readInt(), "Mismatch at index $i")
        }
    }

    // endregion

    // region Cross-zone contentEquals/mismatch tests

    @Test
    fun contentEqualsHeapVsDirect() {
        val heap = PlatformBuffer.allocate(100, AllocationZone.Heap)
        val direct = PlatformBuffer.allocate(100, AllocationZone.Direct)

        heap.writeInt(0x12345678)
        heap.writeString("Hello")
        heap.resetForRead()

        direct.writeInt(0x12345678)
        direct.writeString("Hello")
        direct.resetForRead()

        assertTrue(heap.contentEquals(direct))
        assertTrue(direct.contentEquals(heap))
    }

    @Test
    fun contentEqualsHeapVsDirectDifferent() {
        val heap = PlatformBuffer.allocate(100, AllocationZone.Heap)
        val direct = PlatformBuffer.allocate(100, AllocationZone.Direct)

        heap.writeInt(0x12345678)
        heap.resetForRead()

        direct.writeInt(0x12345679) // Different value
        direct.resetForRead()

        assertFalse(heap.contentEquals(direct))
        assertFalse(direct.contentEquals(heap))
    }

    @Test
    fun mismatchHeapVsDirect() {
        val heap = PlatformBuffer.allocate(100, AllocationZone.Heap)
        val direct = PlatformBuffer.allocate(100, AllocationZone.Direct)

        heap.writeInt(0x12345678)
        heap.writeInt(0xDEADBEEF.toInt())
        heap.writeInt(0x11111111)
        heap.resetForRead()

        direct.writeInt(0x12345678)
        direct.writeInt(0xDEADBEEF.toInt())
        direct.writeInt(0x22222222) // Different at byte 8
        direct.resetForRead()

        assertEquals(8, heap.mismatch(direct))
        assertEquals(8, direct.mismatch(heap))
    }

    @Test
    fun mismatchHeapVsDirectIdentical() {
        val heap = PlatformBuffer.allocate(100, AllocationZone.Heap)
        val direct = PlatformBuffer.allocate(100, AllocationZone.Direct)

        heap.writeLong(0x123456789ABCDEF0L)
        heap.resetForRead()

        direct.writeLong(0x123456789ABCDEF0L)
        direct.resetForRead()

        assertEquals(-1, heap.mismatch(direct))
    }

    // endregion

    // region Cross-zone indexOf tests

    @Test
    fun indexOfBufferHeapInDirect() {
        val haystack = PlatformBuffer.allocate(100, AllocationZone.Direct)
        haystack.writeString("Hello, World!")
        haystack.resetForRead()

        val needle = PlatformBuffer.allocate(10, AllocationZone.Heap)
        needle.writeString("World")
        needle.resetForRead()

        assertEquals(7, haystack.indexOf(needle))
    }

    @Test
    fun indexOfBufferDirectInHeap() {
        val haystack = PlatformBuffer.allocate(100, AllocationZone.Heap)
        haystack.writeString("Hello, World!")
        haystack.resetForRead()

        val needle = PlatformBuffer.allocate(10, AllocationZone.Direct)
        needle.writeString("World")
        needle.resetForRead()

        assertEquals(7, haystack.indexOf(needle))
    }

    @Test
    fun indexOfBufferLargePattern() {
        val haystack = PlatformBuffer.allocate(1024, AllocationZone.Direct)
        // Write some data, then a pattern, then more data
        for (i in 0 until 100) {
            haystack.writeByte(0x00)
        }
        haystack.writeString("PATTERN_TO_FIND")
        for (i in 0 until 100) {
            haystack.writeByte(0xFF.toByte())
        }
        haystack.resetForRead()

        val needle = PlatformBuffer.allocate(20, AllocationZone.Heap)
        needle.writeString("PATTERN_TO_FIND")
        needle.resetForRead()

        assertEquals(100, haystack.indexOf(needle))
    }

    // endregion

    // region Different byte order interactions

    @Test
    fun copyDifferentByteOrders() {
        val sourceBE = PlatformBuffer.allocate(100, byteOrder = ByteOrder.BIG_ENDIAN)
        sourceBE.writeInt(0x12345678)
        sourceBE.resetForRead()

        val destLE = PlatformBuffer.allocate(100, byteOrder = ByteOrder.LITTLE_ENDIAN)
        destLE.write(sourceBE)
        destLE.resetForRead()

        // The bytes are copied as-is, so reading with LE order gives different value
        val bytes = destLE.readByteArray(4)
        assertContentEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), bytes)
    }

    @Test
    fun contentEqualsDifferentByteOrders() {
        // Buffers with different byte orders but same raw bytes should be equal
        val be = PlatformBuffer.allocate(4, byteOrder = ByteOrder.BIG_ENDIAN)
        be.writeByte(0x12)
        be.writeByte(0x34)
        be.writeByte(0x56)
        be.writeByte(0x78)
        be.resetForRead()

        val le = PlatformBuffer.allocate(4, byteOrder = ByteOrder.LITTLE_ENDIAN)
        le.writeByte(0x12)
        le.writeByte(0x34)
        le.writeByte(0x56)
        le.writeByte(0x78)
        le.resetForRead()

        assertTrue(be.contentEquals(le))
    }

    // endregion

    // region Wrap vs allocate interactions

    @Test
    fun copyWrappedToAllocated() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val wrapped = PlatformBuffer.wrap(data)

        val allocated = PlatformBuffer.allocate(8, AllocationZone.Direct)
        allocated.write(wrapped)
        allocated.resetForRead()

        assertContentEquals(data, allocated.readByteArray(8))
    }

    @Test
    fun copyAllocatedToWrapped() {
        val allocated = PlatformBuffer.allocate(8, AllocationZone.Direct)
        allocated.writeInt(0x12345678)
        allocated.writeInt(0xDEADBEEF.toInt())
        allocated.resetForRead()

        val data = ByteArray(8)
        val wrapped = PlatformBuffer.wrap(data)
        wrapped.write(allocated)

        assertEquals(0x12.toByte(), data[0])
        assertEquals(0x34.toByte(), data[1])
        assertEquals(0x56.toByte(), data[2])
        assertEquals(0x78.toByte(), data[3])
    }

    @Test
    fun contentEqualsWrappedVsAllocated() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val wrapped = PlatformBuffer.wrap(data.copyOf())

        val allocated = PlatformBuffer.allocate(5, AllocationZone.Direct)
        allocated.writeBytes(data)
        allocated.resetForRead()

        assertTrue(wrapped.contentEquals(allocated))
    }

    // endregion

    // region readLine tests

    @Test
    fun readLineWithLF() {
        val buffer = PlatformBuffer.allocate(20)
        buffer.writeString("Hello\nWorld")
        buffer.resetForRead()

        assertEquals("Hello", buffer.readLine().toString())
        assertEquals("World", buffer.readLine().toString())
    }

    @Test
    fun readLineWithCRLF() {
        val buffer = PlatformBuffer.allocate(20)
        buffer.writeString("Hello\r\nWorld")
        buffer.resetForRead()

        assertEquals("Hello", buffer.readLine().toString())
        assertEquals("World", buffer.readLine().toString())
    }

    @Test
    fun readLineNoNewline() {
        val buffer = PlatformBuffer.allocate(20)
        buffer.writeString("NoNewline")
        buffer.resetForRead()

        assertEquals("NoNewline", buffer.readLine().toString())
    }

    @Test
    fun readLineEmpty() {
        val buffer = PlatformBuffer.allocate(10)
        buffer.writeString("\n\n")
        buffer.resetForRead()

        assertEquals("", buffer.readLine().toString())
        assertEquals("", buffer.readLine().toString())
    }

    @Test
    fun readLineWithCR() {
        val buffer = PlatformBuffer.allocate(20)
        buffer.writeString("Hello\rWorld")
        buffer.resetForRead()

        assertEquals("Hello", buffer.readLine().toString())
        assertEquals("World", buffer.readLine().toString())
    }

    @Test
    fun readLineMixedEndings() {
        val buffer = PlatformBuffer.allocate(50)
        buffer.writeString("Line1\nLine2\r\nLine3\rLine4")
        buffer.resetForRead()

        assertEquals("Line1", buffer.readLine().toString())
        assertEquals("Line2", buffer.readLine().toString())
        assertEquals("Line3", buffer.readLine().toString())
        assertEquals("Line4", buffer.readLine().toString())
    }

    // endregion

    // region readNumberWithByteSize / writeNumberOfByteSize tests

    @Test
    fun numberWithByteSize1() {
        val buffer = PlatformBuffer.allocate(10)
        buffer.writeNumberOfByteSize(0x42L, 1)
        buffer.resetForRead()
        assertEquals(0x42L, buffer.readNumberWithByteSize(1))
    }

    @Test
    fun numberWithByteSize2() {
        val buffer = PlatformBuffer.allocate(10)
        buffer.writeNumberOfByteSize(0x1234L, 2)
        buffer.resetForRead()
        assertEquals(0x1234L, buffer.readNumberWithByteSize(2))
    }

    @Test
    fun numberWithByteSize3() {
        val buffer = PlatformBuffer.allocate(10)
        buffer.writeNumberOfByteSize(0x123456L, 3)
        buffer.resetForRead()
        assertEquals(0x123456L, buffer.readNumberWithByteSize(3))
    }

    @Test
    fun numberWithByteSize4() {
        val buffer = PlatformBuffer.allocate(10)
        buffer.writeNumberOfByteSize(0x12345678L, 4)
        buffer.resetForRead()
        assertEquals(0x12345678L, buffer.readNumberWithByteSize(4))
    }

    @Test
    fun numberWithByteSize5() {
        val buffer = PlatformBuffer.allocate(10)
        buffer.writeNumberOfByteSize(0x123456789AL, 5)
        buffer.resetForRead()
        assertEquals(0x123456789AL, buffer.readNumberWithByteSize(5))
    }

    @Test
    fun numberWithByteSize6() {
        val buffer = PlatformBuffer.allocate(10)
        buffer.writeNumberOfByteSize(0x123456789ABCL, 6)
        buffer.resetForRead()
        assertEquals(0x123456789ABCL, buffer.readNumberWithByteSize(6))
    }

    @Test
    fun numberWithByteSize7() {
        val buffer = PlatformBuffer.allocate(10)
        buffer.writeNumberOfByteSize(0x123456789ABCDEL, 7)
        buffer.resetForRead()
        assertEquals(0x123456789ABCDEL, buffer.readNumberWithByteSize(7))
    }

    @Test
    fun numberWithByteSize8() {
        val buffer = PlatformBuffer.allocate(10)
        buffer.writeNumberOfByteSize(0x123456789ABCDEF0L, 8)
        buffer.resetForRead()
        assertEquals(0x123456789ABCDEF0L, buffer.readNumberWithByteSize(8))
    }

    @Test
    fun numberWithByteSizeLittleEndian() {
        val buffer = PlatformBuffer.allocate(10, byteOrder = ByteOrder.LITTLE_ENDIAN)
        buffer.writeNumberOfByteSize(0x1234L, 2)
        buffer.resetForRead()
        assertEquals(0x1234L, buffer.readNumberWithByteSize(2))
    }

    @Test
    fun getNumberWithStartIndexAndByteSize() {
        val buffer = PlatformBuffer.allocate(10)
        buffer.writeInt(0) // padding
        buffer.writeNumberOfByteSize(0x1234L, 2)
        buffer.resetForRead()

        assertEquals(0x1234L, buffer.getNumberWithStartIndexAndByteSize(4, 2))
        assertEquals(0, buffer.position()) // Position unchanged
    }

    @Test
    fun setIndexNumberAndByteSize() {
        val buffer = PlatformBuffer.allocate(10)
        buffer.writeInt(0) // padding
        buffer.writeNumberOfByteSize(0L, 2) // placeholder
        buffer.setIndexNumberAndByteSize(4, 0x5678L, 2) // overwrite
        buffer.resetForRead()
        buffer.readInt() // skip padding
        assertEquals(0x5678L, buffer.readNumberWithByteSize(2))
    }

    // endregion

    // region Set by index tests (WriteBuffer)

    @Test
    fun setByteAtIndex() {
        val buffer = PlatformBuffer.allocate(10)
        buffer.fill(0.toByte())
        buffer[3] = 0x42.toByte()
        buffer.resetForRead()

        assertEquals(0.toByte(), buffer.readByte())
        assertEquals(0.toByte(), buffer.readByte())
        assertEquals(0.toByte(), buffer.readByte())
        assertEquals(0x42.toByte(), buffer.readByte())
    }

    @Test
    fun setShortAtIndex() {
        val buffer = PlatformBuffer.allocate(10)
        buffer.fill(0.toByte())
        buffer[2] = 0x1234.toShort()
        buffer.resetForRead()

        assertEquals(0.toShort(), buffer.readShort())
        assertEquals(0x1234.toShort(), buffer.readShort())
    }

    @Test
    fun setIntAtIndex() {
        val buffer = PlatformBuffer.allocate(12)
        buffer.fill(0.toByte())
        buffer[4] = 0x12345678
        buffer.resetForRead()

        assertEquals(0, buffer.readInt())
        assertEquals(0x12345678, buffer.readInt())
    }

    @Test
    fun setLongAtIndex() {
        val buffer = PlatformBuffer.allocate(20)
        buffer.fill(0.toByte())
        buffer[8] = 0x123456789ABCDEF0L
        buffer.resetForRead()

        assertEquals(0L, buffer.readLong())
        assertEquals(0x123456789ABCDEF0L, buffer.readLong())
    }

    @Test
    fun setUByteAtIndex() {
        val buffer = PlatformBuffer.allocate(10)
        buffer.fill(0.toByte())
        buffer[3] = 0xFFu.toUByte()
        buffer.resetForRead()

        repeat(3) { buffer.readByte() }
        assertEquals(0xFFu.toUByte(), buffer.readUnsignedByte())
    }

    @Test
    fun setUShortAtIndex() {
        val buffer = PlatformBuffer.allocate(10)
        buffer.fill(0.toByte())
        buffer[2] = 0xFFFFu.toUShort()
        buffer.resetForRead()

        buffer.readShort()
        assertEquals(0xFFFFu.toUShort(), buffer.readUnsignedShort())
    }

    @Test
    fun setUIntAtIndex() {
        val buffer = PlatformBuffer.allocate(12)
        buffer.fill(0.toByte())
        buffer[4] = 0xFFFFFFFFu
        buffer.resetForRead()

        buffer.readInt()
        assertEquals(0xFFFFFFFFu, buffer.readUnsignedInt())
    }

    @Test
    fun setULongAtIndex() {
        val buffer = PlatformBuffer.allocate(20)
        buffer.fill(0.toByte())
        buffer[8] = 0xFFFFFFFFFFFFFFFFuL
        buffer.resetForRead()

        buffer.readLong()
        assertEquals(0xFFFFFFFFFFFFFFFFuL, buffer.readUnsignedLong())
    }

    @Test
    fun setFloatAtIndex() {
        val buffer = PlatformBuffer.allocate(12)
        buffer.fill(0.toByte())
        buffer[4] = 3.14159f
        buffer.resetForRead()

        buffer.readFloat()
        assertEquals(3.14159f, buffer.readFloat(), 0.00001f)
    }

    @Test
    fun setDoubleAtIndex() {
        val buffer = PlatformBuffer.allocate(20)
        buffer.fill(0.toByte())
        buffer[8] = 3.141592653589793
        buffer.resetForRead()

        buffer.readDouble()
        assertEquals(3.141592653589793, buffer.readDouble(), 0.0000000001)
    }

    // endregion

    // region WriteBuffer unsigned write tests

    @Test
    fun writeUByte() {
        val buffer = PlatformBuffer.allocate(4)
        buffer.writeUByte(0xFFu)
        buffer.writeUByte(0x80u)
        buffer.writeUByte(0x00u)
        buffer.writeUByte(0x7Fu)
        buffer.resetForRead()

        assertEquals(0xFFu.toUByte(), buffer.readUnsignedByte())
        assertEquals(0x80u.toUByte(), buffer.readUnsignedByte())
        assertEquals(0x00u.toUByte(), buffer.readUnsignedByte())
        assertEquals(0x7Fu.toUByte(), buffer.readUnsignedByte())
    }

    @Test
    fun writeUShort() {
        val buffer = PlatformBuffer.allocate(8)
        buffer.writeUShort(0xFFFFu)
        buffer.writeUShort(0x8000u)
        buffer.writeUShort(0x0000u)
        buffer.writeUShort(0x7FFFu)
        buffer.resetForRead()

        assertEquals(0xFFFFu.toUShort(), buffer.readUnsignedShort())
        assertEquals(0x8000u.toUShort(), buffer.readUnsignedShort())
        assertEquals(0x0000u.toUShort(), buffer.readUnsignedShort())
        assertEquals(0x7FFFu.toUShort(), buffer.readUnsignedShort())
    }

    @Test
    fun writeUInt() {
        val buffer = PlatformBuffer.allocate(16)
        buffer.writeUInt(0xFFFFFFFFu)
        buffer.writeUInt(0x80000000u)
        buffer.writeUInt(0x00000000u)
        buffer.writeUInt(0x7FFFFFFFu)
        buffer.resetForRead()

        assertEquals(0xFFFFFFFFu, buffer.readUnsignedInt())
        assertEquals(0x80000000u, buffer.readUnsignedInt())
        assertEquals(0x00000000u, buffer.readUnsignedInt())
        assertEquals(0x7FFFFFFFu, buffer.readUnsignedInt())
    }

    @Test
    fun writeULong() {
        val buffer = PlatformBuffer.allocate(32)
        buffer.writeULong(0xFFFFFFFFFFFFFFFFuL)
        buffer.writeULong(0x8000000000000000uL)
        buffer.writeULong(0x0000000000000000uL)
        buffer.writeULong(0x7FFFFFFFFFFFFFFFuL)
        buffer.resetForRead()

        assertEquals(0xFFFFFFFFFFFFFFFFuL, buffer.readUnsignedLong())
        assertEquals(0x8000000000000000uL, buffer.readUnsignedLong())
        assertEquals(0x0000000000000000uL, buffer.readUnsignedLong())
        assertEquals(0x7FFFFFFFFFFFFFFFuL, buffer.readUnsignedLong())
    }

    @Test
    fun writeFloatAndDouble() {
        val buffer = PlatformBuffer.allocate(24)
        val floatVal1 = 3.14159f
        val floatVal2 = -2.71828f
        val doubleVal1 = 3.141592653589793
        val doubleVal2 = -2.718281828459045

        buffer.writeFloat(floatVal1)
        buffer.writeFloat(floatVal2)
        buffer.writeDouble(doubleVal1)
        buffer.writeDouble(doubleVal2)
        buffer.resetForRead()

        // Note that in Kotlin/JS Float range is wider than "single format" bit layout can represent,
        // so some Float values may lose their accuracy after conversion to bits and back.
        assertTrue { (floatVal1 - buffer.readFloat()).absoluteValue < 0.00001f }
        assertTrue { (floatVal2 - buffer.readFloat()).absoluteValue < 0.00001f }
        assertEquals(doubleVal1, buffer.readDouble())
        assertEquals(doubleVal2, buffer.readDouble())
    }

    // endregion

    // region Fill additional edge cases

    @Test
    fun fillByteNonEightMultiple() {
        val buffer = PlatformBuffer.allocate(13) // Not divisible by 8
        buffer.fill(0x42.toByte())
        buffer.resetForRead()

        repeat(13) {
            assertEquals(0x42.toByte(), buffer.readByte())
        }
    }

    @Test
    fun fillByteZeroRemaining() {
        val buffer = PlatformBuffer.allocate(10)
        repeat(10) { buffer.writeByte(0) }
        // Position is now at limit, remaining is 0
        buffer.fill(0xFF.toByte()) // Should do nothing
        buffer.resetForRead()
        // All bytes should still be 0
        repeat(10) {
            assertEquals(0.toByte(), buffer.readByte())
        }
    }

    // endregion

    // region Little endian tests

    @Test
    fun littleEndianShort() {
        val buffer = PlatformBuffer.allocate(4, byteOrder = ByteOrder.LITTLE_ENDIAN)
        buffer.writeShort(0x1234)
        buffer.resetForRead()

        assertEquals(0x34.toByte(), buffer.readByte())
        assertEquals(0x12.toByte(), buffer.readByte())
    }

    @Test
    fun littleEndianInt() {
        val buffer = PlatformBuffer.allocate(8, byteOrder = ByteOrder.LITTLE_ENDIAN)
        buffer.writeInt(0x12345678)
        buffer.resetForRead()

        assertEquals(0x78.toByte(), buffer.readByte())
        assertEquals(0x56.toByte(), buffer.readByte())
        assertEquals(0x34.toByte(), buffer.readByte())
        assertEquals(0x12.toByte(), buffer.readByte())
    }

    @Test
    fun littleEndianLong() {
        val buffer = PlatformBuffer.allocate(16, byteOrder = ByteOrder.LITTLE_ENDIAN)
        buffer.writeLong(0x123456789ABCDEF0L)
        buffer.resetForRead()

        assertEquals(0xF0.toByte(), buffer.readByte())
        assertEquals(0xDE.toByte(), buffer.readByte())
        assertEquals(0xBC.toByte(), buffer.readByte())
        assertEquals(0x9A.toByte(), buffer.readByte())
        assertEquals(0x78.toByte(), buffer.readByte())
        assertEquals(0x56.toByte(), buffer.readByte())
        assertEquals(0x34.toByte(), buffer.readByte())
        assertEquals(0x12.toByte(), buffer.readByte())
    }

    @Test
    fun littleEndianSetAtIndex() {
        val buffer = PlatformBuffer.allocate(8, byteOrder = ByteOrder.LITTLE_ENDIAN)
        buffer.fill(0.toByte())
        buffer[2] = 0x1234.toShort()
        buffer.resetForRead()

        buffer.readShort() // skip first short
        assertEquals(0x34.toByte(), buffer.readByte())
        assertEquals(0x12.toByte(), buffer.readByte())
    }

    // endregion

    // region Buffer write (copy) tests

    @Test
    fun writeBufferFromReadBuffer() {
        val source = PlatformBuffer.allocate(12) // 4 bytes Int + 8 bytes Long = 12
        source.writeInt(0x12345678)
        source.writeLong(0xDEADBEEFCAFEBABEUL.toLong())
        source.resetForRead()
        source.readShort() // Advance position by 2 bytes

        val dest = PlatformBuffer.allocate(20)
        dest.write(source) // Write remaining 10 bytes
        dest.resetForRead()

        // Should have written remaining bytes from source (after skipping 2 bytes)
        assertEquals(0x5678.toShort(), dest.readShort())
        assertEquals(0xDEADBEEFCAFEBABEUL.toLong(), dest.readLong())
    }

    @Test
    fun writeStringWithCharset() {
        val buffer = PlatformBuffer.allocate(50)
        buffer.writeString("Hello, World!", Charset.UTF8)
        buffer.resetForRead()

        assertEquals("Hello, World!", buffer.readString(13, Charset.UTF8))
    }

    // endregion

    // region ReadBuffer additional coverage

    @Test
    fun readBytesSlice() {
        val buffer = PlatformBuffer.allocate(10)
        for (i in 0 until 10) {
            buffer.writeByte(i.toByte())
        }
        buffer.resetForRead()

        buffer.readByte() // Skip first byte
        val slice = buffer.readBytes(4)
        assertEquals(4, slice.remaining())
        assertEquals(1.toByte(), slice.readByte())
        assertEquals(2.toByte(), slice.readByte())
        assertEquals(3.toByte(), slice.readByte())
        assertEquals(4.toByte(), slice.readByte())
        assertEquals(5, buffer.position()) // Original buffer position advanced
    }

    @Test
    fun readBytesZeroSize() {
        val buffer = PlatformBuffer.allocate(10)
        buffer.writeByte(1)
        buffer.resetForRead()

        val slice = buffer.readBytes(0)
        assertEquals(0, slice.remaining())
        assertEquals(0, buffer.position()) // Position unchanged
    }

    @Test
    fun readBytesNegativeSize() {
        val buffer = PlatformBuffer.allocate(10)
        buffer.writeByte(1)
        buffer.resetForRead()

        val slice = buffer.readBytes(-5)
        assertEquals(0, slice.remaining())
    }

    @Test
    fun getUnsignedByteAtIndex() {
        val buffer = PlatformBuffer.allocate(4)
        buffer.writeByte(0x00.toByte())
        buffer.writeByte(0x7F.toByte())
        buffer.writeByte(0x80.toByte())
        buffer.writeByte(0xFF.toByte())
        buffer.resetForRead()

        assertEquals(0x00u.toUByte(), buffer.getUnsignedByte(0))
        assertEquals(0x7Fu.toUByte(), buffer.getUnsignedByte(1))
        assertEquals(0x80u.toUByte(), buffer.getUnsignedByte(2))
        assertEquals(0xFFu.toUByte(), buffer.getUnsignedByte(3))
    }

    @Test
    fun getUnsignedShortAtIndex() {
        val buffer = PlatformBuffer.allocate(8)
        buffer.writeShort(0x0000.toShort())
        buffer.writeShort(0x7FFF.toShort())
        buffer.writeShort(0x8000.toShort())
        buffer.writeShort(0xFFFF.toShort())
        buffer.resetForRead()

        assertEquals(0x0000u.toUShort(), buffer.getUnsignedShort(0))
        assertEquals(0x7FFFu.toUShort(), buffer.getUnsignedShort(2))
        assertEquals(0x8000u.toUShort(), buffer.getUnsignedShort(4))
        assertEquals(0xFFFFu.toUShort(), buffer.getUnsignedShort(6))
    }

    @Test
    fun getUnsignedIntAtIndex() {
        val buffer = PlatformBuffer.allocate(16)
        buffer.writeInt(0x00000000)
        buffer.writeInt(0x7FFFFFFF)
        buffer.writeInt(0x80000000.toInt())
        buffer.writeInt(0xFFFFFFFF.toInt())
        buffer.resetForRead()

        assertEquals(0x00000000u, buffer.getUnsignedInt(0))
        assertEquals(0x7FFFFFFFu, buffer.getUnsignedInt(4))
        assertEquals(0x80000000u, buffer.getUnsignedInt(8))
        assertEquals(0xFFFFFFFFu, buffer.getUnsignedInt(12))
    }

    @Test
    fun getUnsignedLongAtIndex() {
        val buffer = PlatformBuffer.allocate(16)
        buffer.writeLong(0x0000000000000000L)
        buffer.writeLong(0x7FFFFFFFFFFFFFFFL)
        buffer.resetForRead()

        assertEquals(0x0000000000000000uL, buffer.getUnsignedLong(0))
        assertEquals(0x7FFFFFFFFFFFFFFFuL, buffer.getUnsignedLong(8))
    }

    @Test
    fun getDoubleAtIndex() {
        val buffer = PlatformBuffer.allocate(16)
        buffer.writeDouble(3.14159265358979)
        buffer.writeDouble(-2.71828182845904)
        buffer.resetForRead()

        assertEquals(3.14159265358979, buffer.getDouble(0))
        assertEquals(-2.71828182845904, buffer.getDouble(8))
    }

    // endregion

    // region WriteBuffer additional coverage

    @Test
    fun setUByteAtIndexOperator() {
        val buffer = PlatformBuffer.allocate(4)
        buffer.fill(0)
        buffer[0] = 0xFFu.toUByte()
        buffer[1] = 0x80u.toUByte()
        buffer[2] = 0x00u.toUByte()
        buffer[3] = 0x7Fu.toUByte()
        buffer.resetForRead()

        assertEquals(0xFF.toByte(), buffer.readByte())
        assertEquals(0x80.toByte(), buffer.readByte())
        assertEquals(0x00.toByte(), buffer.readByte())
        assertEquals(0x7F.toByte(), buffer.readByte())
    }

    @Test
    fun setUShortAtIndexOperator() {
        val buffer = PlatformBuffer.allocate(4)
        buffer.fill(0)
        buffer[0] = 0xFFFFu.toUShort()
        buffer[2] = 0x1234u.toUShort()
        buffer.resetForRead()

        assertEquals(0xFFFFu.toUShort(), buffer.readUnsignedShort())
        assertEquals(0x1234u.toUShort(), buffer.readUnsignedShort())
    }

    @Test
    fun setUIntAtIndexOperator() {
        val buffer = PlatformBuffer.allocate(8)
        buffer.fill(0)
        buffer[0] = 0xFFFFFFFFu
        buffer[4] = 0x12345678u
        buffer.resetForRead()

        assertEquals(0xFFFFFFFFu, buffer.readUnsignedInt())
        assertEquals(0x12345678u, buffer.readUnsignedInt())
    }

    @Test
    fun setULongAtIndexOperator() {
        val buffer = PlatformBuffer.allocate(16)
        buffer.fill(0)
        buffer[0] = 0xFFFFFFFFFFFFFFFFuL
        buffer[8] = 0x123456789ABCDEF0uL
        buffer.resetForRead()

        assertEquals(0xFFFFFFFFFFFFFFFFuL, buffer.readUnsignedLong())
        assertEquals(0x123456789ABCDEF0uL, buffer.readUnsignedLong())
    }

    @Test
    fun setFloatAtIndexOperator() {
        val buffer = PlatformBuffer.allocate(8)
        buffer.fill(0)
        buffer[0] = 3.14f
        buffer[4] = -2.71f
        buffer.resetForRead()

        assertTrue { (3.14f - buffer.readFloat()).absoluteValue < 0.001f }
        assertTrue { (-2.71f - buffer.readFloat()).absoluteValue < 0.001f }
    }

    @Test
    fun setDoubleAtIndexOperator() {
        val buffer = PlatformBuffer.allocate(16)
        buffer.fill(0)
        buffer[0] = 3.14159265358979
        buffer[8] = -2.71828182845904
        buffer.resetForRead()

        assertEquals(3.14159265358979, buffer.readDouble())
        assertEquals(-2.71828182845904, buffer.readDouble())
    }

    @Test
    fun writeNumberOfByteSizeBigEndian() {
        val buffer = PlatformBuffer.allocate(8, byteOrder = ByteOrder.BIG_ENDIAN)
        buffer.writeNumberOfByteSize(0x123456L, 3)
        buffer.resetForRead()

        assertEquals(0x12.toByte(), buffer.readByte())
        assertEquals(0x34.toByte(), buffer.readByte())
        assertEquals(0x56.toByte(), buffer.readByte())
    }

    @Test
    fun writeNumberOfByteSizeLittleEndian() {
        val buffer = PlatformBuffer.allocate(8, byteOrder = ByteOrder.LITTLE_ENDIAN)
        buffer.writeNumberOfByteSize(0x123456L, 3)
        buffer.resetForRead()

        assertEquals(0x56.toByte(), buffer.readByte())
        assertEquals(0x34.toByte(), buffer.readByte())
        assertEquals(0x12.toByte(), buffer.readByte())
    }

    @Test
    fun setIndexNumberAndByteSizeMiddle() {
        val buffer = PlatformBuffer.allocate(10, byteOrder = ByteOrder.BIG_ENDIAN)
        buffer.fill(0.toByte())
        buffer.setIndexNumberAndByteSize(2, 0xABCDEFL, 3)
        buffer.resetForRead()

        assertEquals(0.toByte(), buffer.readByte()) // Index 0
        assertEquals(0.toByte(), buffer.readByte()) // Index 1
        assertEquals(0xAB.toByte(), buffer.readByte()) // Index 2
        assertEquals(0xCD.toByte(), buffer.readByte()) // Index 3
        assertEquals(0xEF.toByte(), buffer.readByte()) // Index 4
        assertEquals(0.toByte(), buffer.readByte()) // Index 5
    }

    @Test
    fun fillShortWithRemainder() {
        val buffer = PlatformBuffer.allocate(10) // 10 bytes = 5 shorts, tests remainder path
        buffer.fill(0x1234.toShort())
        buffer.resetForRead()

        repeat(5) {
            assertEquals(0x1234.toShort(), buffer.readShort())
        }
    }

    @Test
    fun fillShortEmpty() {
        val buffer = PlatformBuffer.allocate(8)
        repeat(8) { buffer.writeByte(0) }
        // Position is now at limit, remaining is 0
        buffer.fill(0x1234.toShort()) // Should do nothing
        buffer.resetForRead()
        // All bytes should still be 0
        repeat(8) {
            assertEquals(0.toByte(), buffer.readByte())
        }
    }

    @Test
    fun fillIntWithRemainder() {
        val buffer = PlatformBuffer.allocate(12) // 12 bytes = 3 ints, but 8+4 for bulk+remainder
        buffer.fill(0x12345678)
        buffer.resetForRead()

        repeat(3) {
            assertEquals(0x12345678, buffer.readInt())
        }
    }

    @Test
    fun fillIntEmpty() {
        val buffer = PlatformBuffer.allocate(8)
        repeat(8) { buffer.writeByte(0) }
        buffer.fill(0x12345678) // Should do nothing
        buffer.resetForRead()
        repeat(8) {
            assertEquals(0.toByte(), buffer.readByte())
        }
    }

    @Test
    fun fillLongMultiple() {
        val buffer = PlatformBuffer.allocate(24)
        buffer.fill(0x123456789ABCDEF0L)
        buffer.resetForRead()

        repeat(3) {
            assertEquals(0x123456789ABCDEF0L, buffer.readLong())
        }
    }

    @Test
    fun fillLongEmpty() {
        val buffer = PlatformBuffer.allocate(8)
        repeat(8) { buffer.writeByte(0) }
        buffer.fill(0x123456789ABCDEF0L) // Should do nothing
        buffer.resetForRead()
        repeat(8) {
            assertEquals(0.toByte(), buffer.readByte())
        }
    }

    @Test
    fun writeBytesWithOffsetAndLength() {
        val bytes = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val buffer = PlatformBuffer.allocate(5)
        buffer.writeBytes(bytes, 3, 5) // Write bytes[3..7]
        buffer.resetForRead()

        assertEquals(3.toByte(), buffer.readByte())
        assertEquals(4.toByte(), buffer.readByte())
        assertEquals(5.toByte(), buffer.readByte())
        assertEquals(6.toByte(), buffer.readByte())
        assertEquals(7.toByte(), buffer.readByte())
    }

    // endregion

    // region ReadBuffer little endian coverage

    @Test
    fun readShortLittleEndian() {
        val buffer = PlatformBuffer.allocate(2, byteOrder = ByteOrder.LITTLE_ENDIAN)
        buffer.writeByte(0x34.toByte())
        buffer.writeByte(0x12.toByte())
        buffer.resetForRead()

        assertEquals(0x1234.toShort(), buffer.readShort())
    }

    @Test
    fun getShortLittleEndian() {
        val buffer = PlatformBuffer.allocate(2, byteOrder = ByteOrder.LITTLE_ENDIAN)
        buffer.writeByte(0x34.toByte())
        buffer.writeByte(0x12.toByte())
        buffer.resetForRead()

        assertEquals(0x1234.toShort(), buffer.getShort(0))
    }

    @Test
    fun readIntLittleEndian() {
        val buffer = PlatformBuffer.allocate(4, byteOrder = ByteOrder.LITTLE_ENDIAN)
        buffer.writeByte(0x78.toByte())
        buffer.writeByte(0x56.toByte())
        buffer.writeByte(0x34.toByte())
        buffer.writeByte(0x12.toByte())
        buffer.resetForRead()

        assertEquals(0x12345678, buffer.readInt())
    }

    @Test
    fun getIntLittleEndian() {
        val buffer = PlatformBuffer.allocate(4, byteOrder = ByteOrder.LITTLE_ENDIAN)
        buffer.writeByte(0x78.toByte())
        buffer.writeByte(0x56.toByte())
        buffer.writeByte(0x34.toByte())
        buffer.writeByte(0x12.toByte())
        buffer.resetForRead()

        assertEquals(0x12345678, buffer.getInt(0))
    }

    @Test
    fun readLongLittleEndian() {
        val buffer = PlatformBuffer.allocate(8, byteOrder = ByteOrder.LITTLE_ENDIAN)
        buffer.writeByte(0xF0.toByte())
        buffer.writeByte(0xDE.toByte())
        buffer.writeByte(0xBC.toByte())
        buffer.writeByte(0x9A.toByte())
        buffer.writeByte(0x78.toByte())
        buffer.writeByte(0x56.toByte())
        buffer.writeByte(0x34.toByte())
        buffer.writeByte(0x12.toByte())
        buffer.resetForRead()

        assertEquals(0x123456789ABCDEF0L, buffer.readLong())
    }

    @Test
    fun getLongLittleEndian() {
        val buffer = PlatformBuffer.allocate(8, byteOrder = ByteOrder.LITTLE_ENDIAN)
        buffer.writeByte(0xF0.toByte())
        buffer.writeByte(0xDE.toByte())
        buffer.writeByte(0xBC.toByte())
        buffer.writeByte(0x9A.toByte())
        buffer.writeByte(0x78.toByte())
        buffer.writeByte(0x56.toByte())
        buffer.writeByte(0x34.toByte())
        buffer.writeByte(0x12.toByte())
        buffer.resetForRead()

        assertEquals(0x123456789ABCDEF0L, buffer.getLong(0))
    }

    // endregion

    // region WriteBuffer little endian set at index

    @Test
    fun setShortAtIndexLittleEndian() {
        val buffer = PlatformBuffer.allocate(4, byteOrder = ByteOrder.LITTLE_ENDIAN)
        buffer.fill(0)
        buffer[0] = 0x1234.toShort()
        buffer.resetForRead()

        assertEquals(0x34.toByte(), buffer.readByte())
        assertEquals(0x12.toByte(), buffer.readByte())
    }

    @Test
    fun setIntAtIndexLittleEndian() {
        val buffer = PlatformBuffer.allocate(4, byteOrder = ByteOrder.LITTLE_ENDIAN)
        buffer.fill(0)
        buffer[0] = 0x12345678
        buffer.resetForRead()

        assertEquals(0x78.toByte(), buffer.readByte())
        assertEquals(0x56.toByte(), buffer.readByte())
        assertEquals(0x34.toByte(), buffer.readByte())
        assertEquals(0x12.toByte(), buffer.readByte())
    }

    @Test
    fun setLongAtIndexLittleEndian() {
        val buffer = PlatformBuffer.allocate(8, byteOrder = ByteOrder.LITTLE_ENDIAN)
        buffer.fill(0)
        buffer[0] = 0x123456789ABCDEF0L
        buffer.resetForRead()

        assertEquals(0xF0.toByte(), buffer.readByte())
        assertEquals(0xDE.toByte(), buffer.readByte())
        assertEquals(0xBC.toByte(), buffer.readByte())
        assertEquals(0x9A.toByte(), buffer.readByte())
        assertEquals(0x78.toByte(), buffer.readByte())
        assertEquals(0x56.toByte(), buffer.readByte())
        assertEquals(0x34.toByte(), buffer.readByte())
        assertEquals(0x12.toByte(), buffer.readByte())
    }

    // endregion
}
