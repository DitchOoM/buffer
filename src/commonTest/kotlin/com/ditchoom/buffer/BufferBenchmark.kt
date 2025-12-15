package com.ditchoom.buffer

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State

@State(Scope.Benchmark)
class BufferBenchmark {

    @Benchmark
    fun allocate() {
        PlatformBuffer.allocate(1024)
    }
}
