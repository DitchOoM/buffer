package com.ditchoom.buffer

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State

@State(Scope.Benchmark)
class PlatformBufferBenchmark {

    @Benchmark
    fun slice() {
        val buffer = PlatformBuffer.allocate(1024)
        buffer.slice()
    }
}
