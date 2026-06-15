package com.ditchoom.onebrc

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown

/**
 * The One Billion Row Challenge as a cross-platform benchmark (JVM, Native, JS, WASM via
 * kotlinx-benchmark). Every platform solves the SAME [ROWS]-row dataset (same seed) so the numbers
 * are directly comparable; [ROWS] is kept modest because JS/WASM are single-threaded and in-memory.
 * For the headline large-scale runs use the `onebrcRun` task (JVM) or the native release executable.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
open class OneBrcBenchmark {
    private var path: String = ""

    @Setup
    fun setup() {
        path = onebrcTempFile("bench")
        generateDataset(path, rows = ROWS, seed = 1)
    }

    @TearDown
    fun teardown() {
        onebrcDeleteFile(path)
    }

    @Benchmark
    fun solve(): String = OneBrc.solveFile(path)

    companion object {
        // Same size on every platform so JVM/Native/JS/WASM numbers are directly comparable.
        const val ROWS = 1_000_000L
    }
}
