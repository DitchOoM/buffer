package com.ditchoom.buffer

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State

@State(Scope.Benchmark)
class FragmentedReadBufferBenchmark {
    private val platformBuffer = PlatformBuffer.allocate(
        Byte.SIZE_BYTES + UByte.SIZE_BYTES +
            Short.SIZE_BYTES + UShort.SIZE_BYTES +
            Int.SIZE_BYTES + UInt.SIZE_BYTES +
            Long.SIZE_BYTES + ULong.SIZE_BYTES
    ).apply {
        var index = 0
        this[index] = Byte.MIN_VALUE
        index += Byte.SIZE_BYTES
        this[index] = UByte.MAX_VALUE
        index += UByte.SIZE_BYTES

        this[index] = Short.MIN_VALUE
        index += Short.SIZE_BYTES
        this[index] = UShort.MAX_VALUE
        index += UShort.SIZE_BYTES

        this[index] = Int.MIN_VALUE
        index += Int.SIZE_BYTES
        this[index] = UInt.MAX_VALUE
        index += UInt.SIZE_BYTES

        this[index] = Long.MIN_VALUE
        index += Long.SIZE_BYTES
        this[index] = ULong.MAX_VALUE
    }

    @Benchmark
    fun readAllPrimitivesFromFragmentedBuffer() {
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
