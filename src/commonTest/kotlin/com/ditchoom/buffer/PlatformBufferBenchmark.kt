package com.ditchoom.buffer

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State

@State(Scope.Benchmark)
class PlatformBufferBenchmark {
    private val platformBuffer = PlatformBuffer.allocate(
        Byte.SIZE_BYTES + UByte.SIZE_BYTES +
            Short.SIZE_BYTES + UShort.SIZE_BYTES +
            Int.SIZE_BYTES + UInt.SIZE_BYTES +
            Long.SIZE_BYTES + ULong.SIZE_BYTES
    )
    private val largeByteArray = ByteArray(1024 * 1024)

    @Benchmark
    fun slice() {
        val buffer = PlatformBuffer.allocate(1024)
        buffer.slice()
    }

    @Benchmark
    fun writeAndReadAllPrimitives() {
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
        platformBuffer[index]
        index += Byte.SIZE_BYTES
        platformBuffer.getUnsignedByte(index)
        index += UByte.SIZE_BYTES

        platformBuffer.getShort(index)
        index += Short.SIZE_BYTES
        platformBuffer.getUnsignedShort(index)
        index += UShort.SIZE_BYTES

        platformBuffer.getInt(index)
        index += Int.SIZE_BYTES
        platformBuffer.getUnsignedInt(index)
        index += UInt.SIZE_BYTES

        platformBuffer.getLong(index)
        index += Long.SIZE_BYTES
        platformBuffer.getUnsignedLong(index)

        platformBuffer.readByte()
        platformBuffer.readUnsignedByte()
        platformBuffer.readShort()
        platformBuffer.readUnsignedShort()
        platformBuffer.readInt()
        platformBuffer.readUnsignedInt()
        platformBuffer.readLong()
        platformBuffer.readUnsignedLong()
    }

    @Benchmark
    fun writeAndReadLargeByteArray() {
        val buffer = PlatformBuffer.allocate(largeByteArray.size)
        buffer.writeBytes(largeByteArray)
        buffer.resetForRead()
        buffer.readByteArray(largeByteArray.size)
    }
}
