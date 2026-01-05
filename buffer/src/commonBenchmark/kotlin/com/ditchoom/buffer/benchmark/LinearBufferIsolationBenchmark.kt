package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup

/**
 * Isolation benchmarks to identify the source of the WASM production optimizer bug.
 * Run individual benchmarks to find which operation causes stack overflow.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 1)
@Measurement(iterations = 2)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class LinearBufferIsolationBenchmark {
    private lateinit var heapBuffer: PlatformBuffer

    @Setup
    fun setup() {
        // Only allocate Heap buffer in setup - Direct would trigger the bug
        heapBuffer = PlatformBuffer.allocate(1024, AllocationZone.Heap)
    }

    // Benchmark 1: Just heap allocation - should work
    @Benchmark
    fun heapAllocationOnly(): PlatformBuffer = PlatformBuffer.allocate(1024, AllocationZone.Heap)

    // Benchmark 2: Heap buffer operations - should work
    @Benchmark
    fun heapOperations(): Int {
        heapBuffer.resetForWrite()
        heapBuffer.writeInt(42)
        heapBuffer.resetForRead()
        return heapBuffer.readInt()
    }

    // If the above work, uncomment one at a time to find the culprit:

    // Benchmark 3: Direct allocation (this is likely to crash)
    // @Benchmark
    // fun directAllocation(): PlatformBuffer {
    //     return PlatformBuffer.allocate(1024, AllocationZone.Direct)
    // }
}
