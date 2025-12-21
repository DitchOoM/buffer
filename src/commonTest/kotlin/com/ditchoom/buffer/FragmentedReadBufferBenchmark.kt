package com.ditchoom.buffer

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State

@State(Scope.Benchmark)
class FragmentedReadBufferBenchmark {
    private val bufferSize =
        Byte.SIZE_BYTES + UByte.SIZE_BYTES +
            Short.SIZE_BYTES + UShort.SIZE_BYTES +
            Int.SIZE_BYTES + UInt.SIZE_BYTES +
            Long.SIZE_BYTES + ULong.SIZE_BYTES

    private lateinit var platformBuffer: PlatformBuffer

    @kotlinx.benchmark.Setup
    fun setup() {
        platformBuffer = PlatformBuffer.allocate(bufferSize)
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

        // Set limit to capacity for reading
        platformBuffer.setLimit(platformBuffer.capacity)
    }

    @Benchmark
    fun readAllPrimitivesFromFragmentedBuffer() {
        // Reset position for each iteration
        platformBuffer.position(0)
        val fragmentedReadBuffer = FragmentedReadBuffer(platformBuffer, platformBuffer)
        fragmentedReadBuffer.readByte()
        fragmentedReadBuffer.readUnsignedByte()
        fragmentedReadBuffer.readShort()
        fragmentedReadBuffer.readUnsignedShort()
        fragmentedReadBuffer.readInt()
        fragmentedReadBuffer.readUnsignedInt()
        fragmentedReadBuffer.readLong()
        fragmentedReadBuffer.readUnsignedLong()
    }

    @Benchmark
    fun fragmentedReadBuffer() {
        val buffer1 = PlatformBuffer.allocate(100)
        val buffer2 = PlatformBuffer.allocate(100)
        val fragmentedReadBuffer = FragmentedReadBuffer(buffer1, buffer2)
        fragmentedReadBuffer.readByte()
    }
}
