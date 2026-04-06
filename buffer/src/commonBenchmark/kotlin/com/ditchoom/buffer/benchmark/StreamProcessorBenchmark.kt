package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
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
 * Benchmarks for StreamProcessor measuring the impact of small-chunk coalescing.
 *
 * Key scenarios:
 * - Large chunks (4KB): baseline, should not regress with coalescing
 * - Small chunks (64B): the scenario coalescing optimizes (1MB/64B = 16,384 chunks → ~64)
 * - Protocol parsing: peek + read pattern typical of MQTT/WebSocket framing
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

    // Pre-built byte arrays — chunks are created fresh each iteration from these
    private lateinit var largeChunkData: List<ByteArray> // 256 x 4KB = 1MB
    private lateinit var smallChunkData: List<ByteArray> // 16384 x 64B = 1MB
    private lateinit var protocolFrameData: List<ByteArray> // 1000 x 64B frames

    companion object {
        private const val TOTAL_BYTES = 1024 * 1024 // 1MB
        private const val LARGE_CHUNK = 4096
        private const val SMALL_CHUNK = 64
        private const val FRAME_COUNT = 1000
        private const val FRAME_SIZE = 64 // 4-byte length prefix + 60-byte payload
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

    private fun buildChunkData(chunkSize: Int, totalBytes: Int): List<ByteArray> {
        val count = totalBytes / chunkSize
        return (0 until count).map { i ->
            ByteArray(chunkSize) { j -> ((i * chunkSize + j) and 0xFF).toByte() }
        }
    }

    private fun buildProtocolFrameData(): List<ByteArray> {
        return (0 until FRAME_COUNT).map { i ->
            val data = ByteArray(FRAME_SIZE)
            // Write length prefix (60) as big-endian Int in first 4 bytes
            val payloadLen = FRAME_SIZE - 4
            data[0] = (payloadLen ushr 24).toByte()
            data[1] = (payloadLen ushr 16).toByte()
            data[2] = (payloadLen ushr 8).toByte()
            data[3] = payloadLen.toByte()
            for (j in 4 until FRAME_SIZE) {
                data[j] = ((i + j) and 0xFF).toByte()
            }
            data
        }
    }

    // =========================================================================
    // Append + ReadBuffer (bulk consume)
    // =========================================================================

    /**
     * Append 1MB in 4KB chunks, then readBuffer the entire thing.
     * This is the large-chunk baseline — coalescing should not regress this.
     */
    @Benchmark
    fun appendReadLargeChunks(): Int {
        val processor = StreamProcessor.create(pool)
        for (data in largeChunkData) {
            val chunk = factory.allocate(data.size)
            chunk.writeBytes(data)
            chunk.resetForRead()
            processor.append(chunk)
        }
        val result = processor.readBuffer(TOTAL_BYTES)
        val value = result.get(result.position())
        processor.release()
        return value.toInt()
    }

    /**
     * Append 1MB in 64B chunks, then readBuffer the entire thing.
     * This is the scenario coalescing optimizes: 16,384 chunks → ~64.
     */
    @Benchmark
    fun appendReadSmallChunks(): Int {
        val processor = StreamProcessor.create(pool)
        for (data in smallChunkData) {
            val chunk = factory.allocate(data.size)
            chunk.writeBytes(data)
            chunk.resetForRead()
            processor.append(chunk)
        }
        val result = processor.readBuffer(TOTAL_BYTES)
        val value = result.get(result.position())
        processor.release()
        return value.toInt()
    }

    // =========================================================================
    // Peek at offset (the O(n) → O(1) improvement)
    // =========================================================================

    /**
     * Append 1MB in 4KB chunks, peek at middle offset.
     * Large chunks: offset scan is fast regardless.
     */
    @Benchmark
    fun peekLargeChunks(): Int {
        val processor = StreamProcessor.create(pool)
        for (data in largeChunkData) {
            val chunk = factory.allocate(data.size)
            chunk.writeBytes(data)
            chunk.resetForRead()
            processor.append(chunk)
        }
        val value = processor.peekInt(TOTAL_BYTES / 2)
        processor.release()
        return value
    }

    /**
     * Append 1MB in 64B chunks, peek at middle offset.
     * Without coalescing: scans 8,192 chunks. With coalescing: scans ~32 chunks.
     */
    @Benchmark
    fun peekSmallChunks(): Int {
        val processor = StreamProcessor.create(pool)
        for (data in smallChunkData) {
            val chunk = factory.allocate(data.size)
            chunk.writeBytes(data)
            chunk.resetForRead()
            processor.append(chunk)
        }
        val value = processor.peekInt(TOTAL_BYTES / 2)
        processor.release()
        return value
    }

    // =========================================================================
    // Protocol parsing pattern (interleaved peek + read)
    // =========================================================================

    /**
     * Simulates protocol frame parsing: append 64-byte frames one at a time,
     * peek the 4-byte length header, then readBuffer the payload.
     * This is the real-world pattern for MQTT/WebSocket.
     */
    @Benchmark
    fun protocolParsing(): Long {
        val processor = StreamProcessor.create(pool)
        var sum = 0L
        for (data in protocolFrameData) {
            val chunk = factory.allocate(data.size)
            chunk.writeBytes(data)
            chunk.resetForRead()
            processor.append(chunk)
            // Parse all available frames
            while (processor.available() >= 4) {
                val frameLen = processor.peekInt()
                if (processor.available() < 4 + frameLen) break
                processor.skip(4) // consume header
                processor.readBufferScoped(frameLen) {
                    sum += readByte().toLong()
                }
            }
        }
        processor.release()
        return sum
    }
}
