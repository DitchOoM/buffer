package com.ditchoom.buffer

import kotlin.system.measureNanoTime
import kotlin.test.Test

/**
 * Performance comparison benchmark - works on both main and feature branches
 */
class PerformanceComparison {
    @Test
    fun runAllBenchmarks() {
        println("\n${"=".repeat(60)}")
        println("BUFFER PERFORMANCE BENCHMARK")
        println("${"=".repeat(60)}\n")

        benchmarkAllocation()
        benchmarkSlice()
        benchmarkReadWriteInt()
        benchmarkReadWriteLong()
        benchmarkSequentialAccess()
        benchmarkLargeBufferSlice()

        println("\n${"=".repeat(60)}")
        println("BENCHMARK COMPLETE")
        println("${"=".repeat(60)}\n")
    }

    private fun benchmarkAllocation() {
        val warmup = 10_000
        val iterations = 100_000
        val size = 1024

        // Warmup
        repeat(warmup) {
            val buffer = PlatformBuffer.allocate(size)
            buffer.writeByte(1)
        }

        System.gc()
        Thread.sleep(100)

        val time =
            measureNanoTime {
                repeat(iterations) {
                    val buffer = PlatformBuffer.allocate(size)
                    buffer.writeByte(1)
                }
            }

        println("Allocation (1KB):        ${String.format("%,10d", time / iterations)} ns/op")
    }

    private fun benchmarkSlice() {
        val buffer = PlatformBuffer.allocate(10_000)
        repeat(5_000) { buffer.writeShort(it.toShort()) }
        buffer.resetForRead()

        val warmup = 10_000
        val iterations = 100_000

        // Warmup
        repeat(warmup) {
            buffer.position(0)
            val slice = buffer.slice()
            slice.readByte()
        }

        System.gc()
        Thread.sleep(100)

        val time =
            measureNanoTime {
                repeat(iterations) {
                    buffer.position(0)
                    val slice = buffer.slice()
                    slice.readByte()
                }
            }

        println("Slice (10KB buffer):     ${String.format("%,10d", time / iterations)} ns/op")
    }

    private fun benchmarkReadWriteInt() {
        val buffer = PlatformBuffer.allocate(40_000)
        val warmup = 1_000
        val iterations = 10_000
        val opsPerIteration = 10_000

        // Warmup
        repeat(warmup) {
            buffer.resetForWrite()
            repeat(opsPerIteration) { buffer.writeInt(it) }
            buffer.resetForRead()
            repeat(opsPerIteration) { buffer.readInt() }
        }

        System.gc()
        Thread.sleep(100)

        val writeTime =
            measureNanoTime {
                repeat(iterations) {
                    buffer.resetForWrite()
                    repeat(opsPerIteration) { buffer.writeInt(it) }
                }
            }

        val readTime =
            measureNanoTime {
                repeat(iterations) {
                    buffer.resetForRead()
                    repeat(opsPerIteration) { buffer.readInt() }
                }
            }

        val totalOps = iterations.toLong() * opsPerIteration
        println("WriteInt (10K ops):      ${String.format("%,10d", writeTime / totalOps)} ns/op")
        println("ReadInt (10K ops):       ${String.format("%,10d", readTime / totalOps)} ns/op")
    }

    private fun benchmarkReadWriteLong() {
        val buffer = PlatformBuffer.allocate(80_000)
        val warmup = 1_000
        val iterations = 10_000
        val opsPerIteration = 10_000

        // Warmup
        repeat(warmup) {
            buffer.resetForWrite()
            repeat(opsPerIteration) { buffer.writeLong(it.toLong()) }
            buffer.resetForRead()
            repeat(opsPerIteration) { buffer.readLong() }
        }

        System.gc()
        Thread.sleep(100)

        val writeTime =
            measureNanoTime {
                repeat(iterations) {
                    buffer.resetForWrite()
                    repeat(opsPerIteration) { buffer.writeLong(it.toLong()) }
                }
            }

        val readTime =
            measureNanoTime {
                repeat(iterations) {
                    buffer.resetForRead()
                    repeat(opsPerIteration) { buffer.readLong() }
                }
            }

        val totalOps = iterations.toLong() * opsPerIteration
        println("WriteLong (10K ops):     ${String.format("%,10d", writeTime / totalOps)} ns/op")
        println("ReadLong (10K ops):      ${String.format("%,10d", readTime / totalOps)} ns/op")
    }

    private fun benchmarkSequentialAccess() {
        val size = 1_000_000
        val buffer = PlatformBuffer.allocate(size)
        val warmup = 100
        val iterations = 1_000

        // Fill buffer
        repeat(size) { buffer.writeByte((it % 256).toByte()) }

        // Warmup
        repeat(warmup) {
            buffer.resetForRead()
            var sum = 0L
            repeat(size) { sum += buffer.readByte() }
        }

        System.gc()
        Thread.sleep(100)

        val time =
            measureNanoTime {
                repeat(iterations) {
                    buffer.resetForRead()
                    var sum = 0L
                    repeat(size) { sum += buffer.readByte() }
                }
            }

        val totalBytes = iterations.toLong() * size
        val mbPerSec = (totalBytes * 1_000_000_000.0) / (time * 1024 * 1024)
        println("Sequential read (1MB):   ${String.format("%,10.2f", mbPerSec)} MB/s")
    }

    private fun benchmarkLargeBufferSlice() {
        val sizes = listOf(1_000, 10_000, 100_000, 1_000_000)

        println("\nSlice performance by buffer size:")

        for (size in sizes) {
            val buffer = PlatformBuffer.allocate(size)
            repeat(size / 2) { buffer.writeShort(it.toShort()) }
            buffer.resetForRead()

            val warmup = 1_000
            val iterations = 50_000

            // Warmup
            repeat(warmup) {
                buffer.position(0)
                buffer.slice()
            }

            System.gc()
            Thread.sleep(50)

            val time =
                measureNanoTime {
                    repeat(iterations) {
                        buffer.position(0)
                        buffer.slice()
                    }
                }

            val sizeStr =
                when {
                    size >= 1_000_000 -> "${size / 1_000_000}MB"
                    size >= 1_000 -> "${size / 1_000}KB"
                    else -> "${size}B"
                }
            println("  Slice ($sizeStr):".padEnd(25) + "${String.format("%,10d", time / iterations)} ns/op")
        }
    }
}
