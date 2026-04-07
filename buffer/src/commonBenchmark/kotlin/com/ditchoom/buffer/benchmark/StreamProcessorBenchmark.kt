package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.DefaultStreamProcessor
import com.ditchoom.buffer.stream.StreamProcessor
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.benchmark.Warmup

/**
 * Benchmarks comparing StreamProcessor strategies:
 *
 * - **Default (coalescing)**: copies small chunks into larger buffers, reducing chunk count
 * - **ZeroCopy**: no coalescing (coalesceThreshold=0), relies on peek cache only
 *
 * Each strategy is tested against three workloads:
 * - Large chunks (4KB x 256 = 1MB): baseline, both should perform equally
 * - Small chunks (64B x 16384 = 1MB): coalescing reduces chunks, zero-copy keeps all
 * - Protocol parsing (append → peek → read per frame): real-world pattern
 *
 * Run with: ./gradlew jvmBenchmarkStreamProcessorBenchmark
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class StreamProcessorBenchmark {
    private lateinit var pool: BufferPool
    private lateinit var factory: BufferFactory

    private lateinit var largeChunkData: List<ByteArray>
    private lateinit var smallChunkData: List<ByteArray>
    private lateinit var protocolFrameData: List<ByteArray>

    companion object {
        private const val TOTAL_BYTES = 1024 * 1024
        private const val LARGE_CHUNK = 4096
        private const val SMALL_CHUNK = 64
        private const val FRAME_COUNT = 1000
        private const val FRAME_SIZE = 64
    }

    @Setup
    fun setup() {
        factory = BufferFactory.managed()
        pool = BufferPool(defaultBufferSize = 4096, maxPoolSize = 64)
        largeChunkData = buildChunkData(LARGE_CHUNK, TOTAL_BYTES)
        smallChunkData = buildChunkData(SMALL_CHUNK, TOTAL_BYTES)
        protocolFrameData = buildProtocolFrameData()
    }

    @TearDown
    fun teardown() {
        pool.clear()
    }

    private fun buildChunkData(
        chunkSize: Int,
        totalBytes: Int,
    ): List<ByteArray> {
        val count = totalBytes / chunkSize
        return (0 until count).map { i ->
            ByteArray(chunkSize) { j -> ((i * chunkSize + j) and 0xFF).toByte() }
        }
    }

    private fun buildProtocolFrameData(): List<ByteArray> =
        (0 until FRAME_COUNT).map { i ->
            val data = ByteArray(FRAME_SIZE)
            val payloadLen = FRAME_SIZE - 4
            data[0] = (payloadLen ushr 24).toByte()
            data[1] = (payloadLen ushr 16).toByte()
            data[2] = (payloadLen ushr 8).toByte()
            data[3] = payloadLen.toByte()
            for (j in 4 until FRAME_SIZE) data[j] = ((i + j) and 0xFF).toByte()
            data
        }

    private fun appendChunks(
        processor: StreamProcessor,
        chunkData: List<ByteArray>,
    ) {
        for (data in chunkData) {
            val chunk = factory.allocate(data.size)
            chunk.writeBytes(data)
            chunk.resetForRead()
            processor.append(chunk)
        }
    }

    // =========================================================================
    // Default (adaptive coalescing)
    // =========================================================================

    @Benchmark
    fun coalesceAppendReadLarge(): Int {
        val p = StreamProcessor.create(pool)
        appendChunks(p, largeChunkData)
        val r = p.readBuffer(TOTAL_BYTES)
        val v = r.get(r.position()).toInt()
        p.release()
        return v
    }

    @Benchmark
    fun coalesceAppendReadSmall(): Int {
        val p = StreamProcessor.create(pool)
        appendChunks(p, smallChunkData)
        val r = p.readBuffer(TOTAL_BYTES)
        val v = r.get(r.position()).toInt()
        p.release()
        return v
    }

    @Benchmark
    fun coalescePeekSmall(): Int {
        val p = StreamProcessor.create(pool)
        appendChunks(p, smallChunkData)
        val v = p.peekInt(TOTAL_BYTES / 2)
        p.release()
        return v
    }

    @Benchmark
    fun coalesceProtocol(): Long {
        val p = StreamProcessor.create(pool)
        var sum = 0L
        for (data in protocolFrameData) {
            val chunk = factory.allocate(data.size)
            chunk.writeBytes(data)
            chunk.resetForRead()
            p.append(chunk)
            while (p.available() >= 4) {
                val frameLen = p.peekInt()
                if (p.available() < 4 + frameLen) break
                p.skip(4)
                p.readBufferScoped(frameLen) { sum += readByte().toLong() }
            }
        }
        p.release()
        return sum
    }

    // =========================================================================
    // Zero-copy (coalescing disabled, peek cache only)
    // =========================================================================

    @Benchmark
    fun zeroCopyAppendReadLarge(): Int {
        val p = DefaultStreamProcessor(pool, coalesceThreshold = 0)
        appendChunks(p, largeChunkData)
        val r = p.readBuffer(TOTAL_BYTES)
        val v = r.get(r.position()).toInt()
        p.release()
        return v
    }

    @Benchmark
    fun zeroCopyAppendReadSmall(): Int {
        val p = DefaultStreamProcessor(pool, coalesceThreshold = 0)
        appendChunks(p, smallChunkData)
        val r = p.readBuffer(TOTAL_BYTES)
        val v = r.get(r.position()).toInt()
        p.release()
        return v
    }

    @Benchmark
    fun zeroCopyPeekSmall(): Int {
        val p = DefaultStreamProcessor(pool, coalesceThreshold = 0)
        appendChunks(p, smallChunkData)
        val v = p.peekInt(TOTAL_BYTES / 2)
        p.release()
        return v
    }

    @Benchmark
    fun zeroCopyProtocol(): Long {
        val p = DefaultStreamProcessor(pool, coalesceThreshold = 0)
        var sum = 0L
        for (data in protocolFrameData) {
            val chunk = factory.allocate(data.size)
            chunk.writeBytes(data)
            chunk.resetForRead()
            p.append(chunk)
            while (p.available() >= 4) {
                val frameLen = p.peekInt()
                if (p.available() < 4 + frameLen) break
                p.skip(4)
                p.readBufferScoped(frameLen) { sum += readByte().toLong() }
            }
        }
        p.release()
        return sum
    }
}
