package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Throughput regression tests to ensure bounds checks don't introduce
 * measurable overhead on hot paths (BOUNDS_CHECK_REGRESSIONS.md).
 *
 * Each test validates that a high-volume write pattern completes within
 * a generous time budget. These are NOT micro-benchmarks — they catch
 * catastrophic regressions (e.g., 10x slowdown from double-checking),
 * not small percentage changes.
 */
class BoundsCheckThroughputTests {
    @Test
    fun smallChunkWriteThroughput() {
        val buffer = BufferFactory.Default.allocate(1024 * 1024) // 1MB
        val chunk = BufferFactory.Default.allocate(64)
        repeat(64) { chunk.writeByte(0x42) }
        chunk.resetForRead()

        val mark = TimeSource.Monotonic.markNow()
        // Write 16,384 × 64-byte chunks = 1MB
        repeat(16384) {
            chunk.position(0)
            buffer.write(chunk)
        }
        val elapsed = mark.elapsedNow()

        assertTrue(
            elapsed.inWholeSeconds < 10,
            "16K × 64-byte writes took $elapsed, expected < 10s",
        )
    }

    @Test
    fun singleByteWriteThroughput() {
        val buffer = BufferFactory.Default.allocate(256 * 1024) // 256KB
        val mark = TimeSource.Monotonic.markNow()
        repeat(256 * 1024) {
            buffer.writeByte(0x42)
        }
        val elapsed = mark.elapsedNow()

        assertTrue(
            elapsed.inWholeSeconds < 10,
            "256K single-byte writes took $elapsed, expected < 10s",
        )
    }

    @Test
    fun multiByteWriteThroughput() {
        val buffer = BufferFactory.Default.allocate(256 * 1024) // 256KB
        val mark = TimeSource.Monotonic.markNow()
        // Write 32K ints = 128KB
        repeat(32 * 1024) {
            buffer.writeInt(0x12345678)
        }
        val elapsed = mark.elapsedNow()

        assertTrue(
            elapsed.inWholeSeconds < 10,
            "32K writeInt calls took $elapsed, expected < 10s",
        )
    }

    @Test
    fun managedBufferWriteThroughput() {
        val buffer = BufferFactory.managed().allocate(256 * 1024) // 256KB
        val mark = TimeSource.Monotonic.markNow()
        repeat(256 * 1024) {
            buffer.writeByte(0x42)
        }
        val elapsed = mark.elapsedNow()

        assertTrue(
            elapsed.inWholeSeconds < 10,
            "256K managed single-byte writes took $elapsed, expected < 10s",
        )
    }
}
