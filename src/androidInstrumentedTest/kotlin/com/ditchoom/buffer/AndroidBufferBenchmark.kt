package com.ditchoom.buffer

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android-specific benchmarks using AndroidX Benchmark library.
 * Run with: ./gradlew :connectedCheck -Pandroid.testInstrumentationRunnerArguments.class=com.ditchoom.buffer.AndroidBufferBenchmark
 */
@RunWith(AndroidJUnit4::class)
class AndroidBufferBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    @Test
    fun allocateHeapBuffer() {
        benchmarkRule.measureRepeated {
            PlatformBuffer.allocate(1024, AllocationZone.Heap)
        }
    }

    @Test
    fun allocateDirectBuffer() {
        benchmarkRule.measureRepeated {
            PlatformBuffer.allocate(1024, AllocationZone.Direct)
        }
    }

    @Test
    fun writeAndReadInt() {
        val buffer = PlatformBuffer.allocate(1024)
        benchmarkRule.measureRepeated {
            buffer.resetForWrite()
            repeat(256) { buffer.writeInt(it) }
            buffer.resetForRead()
            repeat(256) { buffer.readInt() }
        }
    }

    @Test
    fun writeAndReadLong() {
        val buffer = PlatformBuffer.allocate(2048)
        benchmarkRule.measureRepeated {
            buffer.resetForWrite()
            repeat(256) { buffer.writeLong(it.toLong()) }
            buffer.resetForRead()
            repeat(256) { buffer.readLong() }
        }
    }

    @Test
    fun sliceBuffer() {
        val buffer = PlatformBuffer.allocate(10240)
        buffer.writeBytes(ByteArray(10240))
        buffer.resetForRead()
        benchmarkRule.measureRepeated {
            buffer.position(0)
            buffer.slice()
        }
    }

    @Test
    fun writeByteArray() {
        val buffer = PlatformBuffer.allocate(1024 * 1024)
        val data = ByteArray(1024 * 1024)
        benchmarkRule.measureRepeated {
            buffer.resetForWrite()
            buffer.writeBytes(data)
        }
    }

    @Test
    fun readByteArray() {
        val buffer = PlatformBuffer.allocate(1024 * 1024)
        buffer.writeBytes(ByteArray(1024 * 1024))
        buffer.resetForRead()
        benchmarkRule.measureRepeated {
            buffer.position(0)
            buffer.readByteArray(1024 * 1024)
        }
    }

    @Test
    fun mixedOperations() {
        val buffer = PlatformBuffer.allocate(100_000)
        benchmarkRule.measureRepeated {
            buffer.resetForWrite()
            repeat(100) {
                buffer.writeByte(1)
                buffer.writeShort(2)
                buffer.writeInt(3)
                buffer.writeLong(4)
            }
            buffer.resetForRead()
            repeat(100) {
                buffer.readByte()
                buffer.readShort()
                buffer.readInt()
                buffer.readLong()
            }
        }
    }
}
