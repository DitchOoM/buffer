package com.ditchoom.buffer.flow.benchmark

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.flow.asStringFlow
import com.ditchoom.buffer.flow.lines
import com.ditchoom.buffer.flow.mapBuffer
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Benchmarks for buffer-flow extensions.
 *
 * Primary concern: [lines] does string concatenation per chunk (`remainder + chunk`).
 * We measure throughput at different chunk sizes and line counts to detect regressions.
 *
 * Each benchmark collects a flow to completion, simulating real-world usage.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class FlowExtensionsBenchmark {
    // Pre-built string chunks for lines() benchmarks
    private lateinit var singleChunkSmall: List<String> // 10 lines in 1 chunk
    private lateinit var singleChunkLarge: List<String> // 1000 lines in 1 chunk
    private lateinit var manyChunksSmall: List<String> // 10 lines across ~40 chunks (simulates small TCP reads)
    private lateinit var manyChunksLarge: List<String> // 1000 lines across ~4000 chunks

    // Pre-built buffers for mapBuffer/asStringFlow benchmarks
    private lateinit var buffers4KB: List<PlatformBuffer>
    private lateinit var buffers64KB: List<PlatformBuffer>

    @Setup
    fun setup() {
        // --- lines() data ---
        val smallLines = buildLines(10, 80) // 10 lines, ~80 chars each
        val largeLines = buildLines(1000, 80) // 1000 lines, ~80 chars each

        // Single chunk: all lines in one string
        singleChunkSmall = listOf(smallLines)
        singleChunkLarge = listOf(largeLines)

        // Many small chunks: split into ~20-byte pieces (simulates fragmented TCP reads)
        manyChunksSmall = splitIntoChunks(smallLines, 20)
        manyChunksLarge = splitIntoChunks(largeLines, 20)

        // --- mapBuffer / asStringFlow data ---
        buffers4KB = buildBufferList(10, 4 * 1024) // 10 buffers of 4KB each
        buffers64KB = buildBufferList(10, 64 * 1024) // 10 buffers of 64KB each
    }

    // ===== lines() benchmarks =====

    @Benchmark
    fun linesSingleChunk10Lines(): Int =
        runBlocking {
            flowOfStrings(singleChunkSmall).lines().toList().size
        }

    @Benchmark
    fun linesSingleChunk1000Lines(): Int =
        runBlocking {
            flowOfStrings(singleChunkLarge).lines().toList().size
        }

    @Benchmark
    fun linesManyChunks10Lines(): Int =
        runBlocking {
            flowOfStrings(manyChunksSmall).lines().toList().size
        }

    @Benchmark
    fun linesManyChunks1000Lines(): Int =
        runBlocking {
            flowOfStrings(manyChunksLarge).lines().toList().size
        }

    // ===== mapBuffer() benchmarks =====

    @Benchmark
    fun mapBufferIdentity4KB(): Int =
        runBlocking {
            var totalBytes = 0
            flowOfBuffers(buffers4KB)
                .mapBuffer { it }
                .collect { buf ->
                    totalBytes += buf.remaining()
                }
            totalBytes
        }

    @Benchmark
    fun mapBufferIdentity64KB(): Int =
        runBlocking {
            var totalBytes = 0
            flowOfBuffers(buffers64KB)
                .mapBuffer { it }
                .collect { buf ->
                    totalBytes += buf.remaining()
                }
            totalBytes
        }

    // ===== asStringFlow() benchmarks =====

    @Benchmark
    fun asStringFlow4KB(): Int =
        runBlocking {
            var totalChars = 0
            flowOfBuffers(buffers4KB)
                .asStringFlow()
                .collect { s ->
                    totalChars += s.length
                }
            totalChars
        }

    @Benchmark
    fun asStringFlow64KB(): Int =
        runBlocking {
            var totalChars = 0
            flowOfBuffers(buffers64KB)
                .asStringFlow()
                .collect { s ->
                    totalChars += s.length
                }
            totalChars
        }

    // ===== Composed pipeline: mapBuffer -> asStringFlow -> lines =====

    @Benchmark
    fun composedPipeline4KB(): Int =
        runBlocking {
            flowOfBuffers(buffers4KB)
                .mapBuffer { it }
                .asStringFlow()
                .lines()
                .toList()
                .size
        }

    @Benchmark
    fun composedPipeline64KB(): Int =
        runBlocking {
            flowOfBuffers(buffers64KB)
                .mapBuffer { it }
                .asStringFlow()
                .lines()
                .toList()
                .size
        }

    // ===== Helpers =====

    private fun buildLines(
        lineCount: Int,
        lineLength: Int,
    ): String {
        val words =
            arrayOf(
                "the",
                "quick",
                "brown",
                "fox",
                "jumps",
                "over",
                "lazy",
                "dog",
                "hello",
                "world",
                "streaming",
                "benchmark",
                "buffer",
                "flow",
                "kotlin",
                "multiplatform",
                "coroutines",
                "suspend",
                "collect",
            )
        val sb = StringBuilder(lineCount * (lineLength + 1))
        var wordIdx = 0
        repeat(lineCount) {
            val lineSb = StringBuilder(lineLength)
            while (lineSb.length < lineLength) {
                if (lineSb.isNotEmpty()) lineSb.append(' ')
                lineSb.append(words[wordIdx % words.size])
                wordIdx++
            }
            sb.append(lineSb.substring(0, lineLength))
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun splitIntoChunks(
        text: String,
        chunkSize: Int,
    ): List<String> {
        val chunks = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            chunks.add(text.substring(i, minOf(i + chunkSize, text.length)))
            i += chunkSize
        }
        return chunks
    }

    private fun buildBufferList(
        count: Int,
        sizeBytes: Int,
    ): List<PlatformBuffer> {
        // Build a text payload with embedded newlines (so composed pipeline has lines to find)
        val text = buildLines(sizeBytes / 81, 80) // ~81 bytes per line (80 chars + \n)
        val bytes = text.encodeToByteArray()
        return (0 until count).map {
            val buf = PlatformBuffer.allocate(sizeBytes, AllocationZone.Direct)
            // Write as many bytes as fit
            val toWrite = minOf(bytes.size, sizeBytes)
            buf.writeBytes(bytes, 0, toWrite)
            // Pad remaining with spaces if needed
            repeat(sizeBytes - toWrite) { buf.writeByte(' '.code.toByte()) }
            buf.resetForRead()
            buf
        }
    }

    private fun flowOfStrings(chunks: List<String>): Flow<String> =
        flow {
            for (chunk in chunks) {
                emit(chunk)
            }
        }

    private fun flowOfBuffers(buffers: List<PlatformBuffer>): Flow<ReadBuffer> =
        flow {
            for (buf in buffers) {
                buf.position(0)
                emit(buf)
            }
        }
}
