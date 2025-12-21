package com.ditchoom.buffer

import kotlin.system.measureNanoTime
import kotlin.test.Test

/**
 * Compares UnsafeBuffer vs PlatformBuffer (safe) performance
 */
class UnsafeVsSafeBenchmark {
    @Test
    fun compareUnsafeVsSafe() {
        println("\n${"=".repeat(60)}")
        println("UNSAFE vs SAFE BUFFER COMPARISON")
        println("${"=".repeat(60)}\n")

        compareReadWriteInt()
        compareReadWriteLong()
        compareSequentialBytes()
        compareMixedOperations()

        println("\n${"=".repeat(60)}")
        println("BENCHMARK COMPLETE")
        println("${"=".repeat(60)}\n")
    }

    private fun compareReadWriteInt() {
        val size = 40_000
        val warmup = 1_000
        val iterations = 10_000
        val opsPerIteration = 10_000

        val safeBuffer = PlatformBuffer.allocate(size)

        // Use withBuffer for unsafe
        var unsafeWriteTime = 0L
        var unsafeReadTime = 0L

        DefaultUnsafeBuffer.withBuffer(size) { unsafeBuffer ->
            // Warmup
            repeat(warmup) {
                safeBuffer.resetForWrite()
                repeat(opsPerIteration) { safeBuffer.writeInt(it) }
                safeBuffer.resetForRead()
                repeat(opsPerIteration) { safeBuffer.readInt() }

                unsafeBuffer.resetForWrite()
                repeat(opsPerIteration) { unsafeBuffer.writeInt(it) }
                unsafeBuffer.resetForRead()
                repeat(opsPerIteration) { unsafeBuffer.readInt() }
            }

            System.gc()
            Thread.sleep(100)

            // Safe write
            val safeWriteTime =
                measureNanoTime {
                    repeat(iterations) {
                        safeBuffer.resetForWrite()
                        repeat(opsPerIteration) { safeBuffer.writeInt(it) }
                    }
                }

            // Unsafe write
            unsafeWriteTime =
                measureNanoTime {
                    repeat(iterations) {
                        unsafeBuffer.resetForWrite()
                        repeat(opsPerIteration) { unsafeBuffer.writeInt(it) }
                    }
                }

            // Safe read
            val safeReadTime =
                measureNanoTime {
                    repeat(iterations) {
                        safeBuffer.resetForRead()
                        repeat(opsPerIteration) { safeBuffer.readInt() }
                    }
                }

            // Unsafe read
            unsafeReadTime =
                measureNanoTime {
                    repeat(iterations) {
                        unsafeBuffer.resetForRead()
                        repeat(opsPerIteration) { unsafeBuffer.readInt() }
                    }
                }

            val totalOps = iterations.toLong() * opsPerIteration

            println("WriteInt (10K ops):")
            println("  Safe:   ${String.format("%,10d", safeWriteTime / totalOps)} ns/op")
            println("  Unsafe: ${String.format("%,10d", unsafeWriteTime / totalOps)} ns/op")
            println("  Ratio:  ${String.format("%.2f", safeWriteTime.toDouble() / unsafeWriteTime)}x")
            println()

            println("ReadInt (10K ops):")
            println("  Safe:   ${String.format("%,10d", safeReadTime / totalOps)} ns/op")
            println("  Unsafe: ${String.format("%,10d", unsafeReadTime / totalOps)} ns/op")
            println("  Ratio:  ${String.format("%.2f", safeReadTime.toDouble() / unsafeReadTime)}x")
            println()
        }
    }

    private fun compareReadWriteLong() {
        val size = 80_000
        val warmup = 1_000
        val iterations = 10_000
        val opsPerIteration = 10_000

        val safeBuffer = PlatformBuffer.allocate(size)

        DefaultUnsafeBuffer.withBuffer(size) { unsafeBuffer ->
            // Warmup
            repeat(warmup) {
                safeBuffer.resetForWrite()
                repeat(opsPerIteration) { safeBuffer.writeLong(it.toLong()) }
                safeBuffer.resetForRead()
                repeat(opsPerIteration) { safeBuffer.readLong() }

                unsafeBuffer.resetForWrite()
                repeat(opsPerIteration) { unsafeBuffer.writeLong(it.toLong()) }
                unsafeBuffer.resetForRead()
                repeat(opsPerIteration) { unsafeBuffer.readLong() }
            }

            System.gc()
            Thread.sleep(100)

            val safeWriteTime =
                measureNanoTime {
                    repeat(iterations) {
                        safeBuffer.resetForWrite()
                        repeat(opsPerIteration) { safeBuffer.writeLong(it.toLong()) }
                    }
                }

            val unsafeWriteTime =
                measureNanoTime {
                    repeat(iterations) {
                        unsafeBuffer.resetForWrite()
                        repeat(opsPerIteration) { unsafeBuffer.writeLong(it.toLong()) }
                    }
                }

            val safeReadTime =
                measureNanoTime {
                    repeat(iterations) {
                        safeBuffer.resetForRead()
                        repeat(opsPerIteration) { safeBuffer.readLong() }
                    }
                }

            val unsafeReadTime =
                measureNanoTime {
                    repeat(iterations) {
                        unsafeBuffer.resetForRead()
                        repeat(opsPerIteration) { unsafeBuffer.readLong() }
                    }
                }

            val totalOps = iterations.toLong() * opsPerIteration

            println("WriteLong (10K ops):")
            println("  Safe:   ${String.format("%,10d", safeWriteTime / totalOps)} ns/op")
            println("  Unsafe: ${String.format("%,10d", unsafeWriteTime / totalOps)} ns/op")
            println("  Ratio:  ${String.format("%.2f", safeWriteTime.toDouble() / unsafeWriteTime)}x")
            println()

            println("ReadLong (10K ops):")
            println("  Safe:   ${String.format("%,10d", safeReadTime / totalOps)} ns/op")
            println("  Unsafe: ${String.format("%,10d", unsafeReadTime / totalOps)} ns/op")
            println("  Ratio:  ${String.format("%.2f", safeReadTime.toDouble() / unsafeReadTime)}x")
            println()
        }
    }

    private fun compareSequentialBytes() {
        val size = 1_000_000
        val warmup = 100
        val iterations = 500

        val safeBuffer = PlatformBuffer.allocate(size)

        DefaultUnsafeBuffer.withBuffer(size) { unsafeBuffer ->
            // Fill buffers
            repeat(size) { safeBuffer.writeByte((it % 256).toByte()) }
            repeat(size) { unsafeBuffer.writeByte((it % 256).toByte()) }

            // Warmup
            repeat(warmup) {
                safeBuffer.resetForRead()
                var sum = 0L
                repeat(size) { sum += safeBuffer.readByte() }

                unsafeBuffer.resetForRead()
                sum = 0L
                repeat(size) { sum += unsafeBuffer.readByte() }
            }

            System.gc()
            Thread.sleep(100)

            val safeTime =
                measureNanoTime {
                    repeat(iterations) {
                        safeBuffer.resetForRead()
                        var sum = 0L
                        repeat(size) { sum += safeBuffer.readByte() }
                    }
                }

            val unsafeTime =
                measureNanoTime {
                    repeat(iterations) {
                        unsafeBuffer.resetForRead()
                        var sum = 0L
                        repeat(size) { sum += unsafeBuffer.readByte() }
                    }
                }

            val safeMbPerSec = (iterations.toLong() * size * 1_000_000_000.0) / (safeTime * 1024 * 1024)
            val unsafeMbPerSec = (iterations.toLong() * size * 1_000_000_000.0) / (unsafeTime * 1024 * 1024)

            println("Sequential read (1MB):")
            println("  Safe:   ${String.format("%,10.2f", safeMbPerSec)} MB/s")
            println("  Unsafe: ${String.format("%,10.2f", unsafeMbPerSec)} MB/s")
            println("  Ratio:  ${String.format("%.2f", unsafeMbPerSec / safeMbPerSec)}x")
            println()
        }
    }

    private fun compareMixedOperations() {
        val size = 100_000
        val warmup = 1_000
        val iterations = 10_000

        val safeBuffer = PlatformBuffer.allocate(size)

        DefaultUnsafeBuffer.withBuffer(size) { unsafeBuffer ->
            // Warmup
            repeat(warmup) {
                safeBuffer.resetForWrite()
                repeat(100) {
                    safeBuffer.writeByte(1)
                    safeBuffer.writeShort(2)
                    safeBuffer.writeInt(3)
                    safeBuffer.writeLong(4)
                }
                safeBuffer.resetForRead()
                repeat(100) {
                    safeBuffer.readByte()
                    safeBuffer.readShort()
                    safeBuffer.readInt()
                    safeBuffer.readLong()
                }

                unsafeBuffer.resetForWrite()
                repeat(100) {
                    unsafeBuffer.writeByte(1)
                    unsafeBuffer.writeShort(2)
                    unsafeBuffer.writeInt(3)
                    unsafeBuffer.writeLong(4)
                }
                unsafeBuffer.resetForRead()
                repeat(100) {
                    unsafeBuffer.readByte()
                    unsafeBuffer.readShort()
                    unsafeBuffer.readInt()
                    unsafeBuffer.readLong()
                }
            }

            System.gc()
            Thread.sleep(100)

            val safeTime =
                measureNanoTime {
                    repeat(iterations) {
                        safeBuffer.resetForWrite()
                        repeat(100) {
                            safeBuffer.writeByte(1)
                            safeBuffer.writeShort(2)
                            safeBuffer.writeInt(3)
                            safeBuffer.writeLong(4)
                        }
                        safeBuffer.resetForRead()
                        repeat(100) {
                            safeBuffer.readByte()
                            safeBuffer.readShort()
                            safeBuffer.readInt()
                            safeBuffer.readLong()
                        }
                    }
                }

            val unsafeTime =
                measureNanoTime {
                    repeat(iterations) {
                        unsafeBuffer.resetForWrite()
                        repeat(100) {
                            unsafeBuffer.writeByte(1)
                            unsafeBuffer.writeShort(2)
                            unsafeBuffer.writeInt(3)
                            unsafeBuffer.writeLong(4)
                        }
                        unsafeBuffer.resetForRead()
                        repeat(100) {
                            unsafeBuffer.readByte()
                            unsafeBuffer.readShort()
                            unsafeBuffer.readInt()
                            unsafeBuffer.readLong()
                        }
                    }
                }

            println("Mixed ops (byte+short+int+long x100):")
            println("  Safe:   ${String.format("%,10d", safeTime / iterations)} ns/iteration")
            println("  Unsafe: ${String.format("%,10d", unsafeTime / iterations)} ns/iteration")
            println("  Ratio:  ${String.format("%.2f", safeTime.toDouble() / unsafeTime)}x")
            println()
        }
    }
}
