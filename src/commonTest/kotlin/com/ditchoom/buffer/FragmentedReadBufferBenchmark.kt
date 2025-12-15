package com.ditchoom.buffer

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State

@State(Scope.Benchmark)
class FragmentedReadBufferBenchmark {
    @Benchmark
    fun fragmentedReadBuffer() {
        val buffer1 = PlatformBuffer.allocate(100)
        val buffer2 = PlatformBuffer.allocate(100)
        val fragmentedReadBuffer = FragmentedReadBuffer(buffer1, buffer2)
        fragmentedReadBuffer.readByte()
    }
}
