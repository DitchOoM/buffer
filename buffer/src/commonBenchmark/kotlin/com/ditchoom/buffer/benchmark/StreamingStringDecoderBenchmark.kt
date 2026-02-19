package com.ditchoom.buffer.benchmark

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.StreamingStringDecoder
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
 * Benchmarks for StreamingStringDecoder comparing platform implementations.
 *
 * Scenarios:
 * 1. ASCII decoding — 64KB of pure ASCII text (Direct buffer)
 * 2. Mixed UTF-8 decoding — 64KB of mixed ASCII + multibyte (CJK, accented)
 * 3. Emoji-heavy decoding — 64KB with frequent 4-byte emoji sequences
 * 4. Chunked streaming — 64KB ASCII split into 1KB chunks
 * 5. Baseline: readString() — Same 64KB ASCII using buffer.readString() for comparison
 *
 * Bottleneck isolation:
 * 6. Heap vs Direct — Tests if ByteArray copy from native memory is the bottleneck
 * 7. Chunk size scaling — 256B/4KB/16KB chunks to measure per-call allocation overhead
 * 8. Heap baseline readString — Confirms readString advantage is about the copy path
 *
 * Run with:
 *   ./gradlew :buffer:jvmBenchmarkStreamingBenchmark
 *   ./gradlew :buffer:macosArm64BenchmarkStreamingBenchmark
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class StreamingStringDecoderBenchmark {
    private val size64k = 64 * 1024
    private val chunkSize = 1024

    private lateinit var asciiBuffer: PlatformBuffer
    private lateinit var asciiHeapBuffer: PlatformBuffer
    private lateinit var mixedUtf8Buffer: PlatformBuffer
    private lateinit var emojiBuffer: PlatformBuffer
    private lateinit var asciiChunks: List<PlatformBuffer>
    private lateinit var asciiChunks256: List<PlatformBuffer>
    private lateinit var asciiChunks4k: List<PlatformBuffer>
    private lateinit var asciiChunks16k: List<PlatformBuffer>

    private lateinit var decoder: StreamingStringDecoder
    private val destination = StringBuilder(size64k)

    @Setup
    fun setup() {
        decoder = StreamingStringDecoder()

        // ASCII buffer — repeating printable ASCII written directly
        asciiBuffer = PlatformBuffer.allocate(size64k, AllocationZone.Direct)
        for (i in 0 until size64k) {
            asciiBuffer.writeByte((32 + (i % 95)).toByte())
        }
        asciiBuffer.resetForRead()

        // ASCII Heap buffer — bulk copy from Direct buffer
        asciiHeapBuffer = PlatformBuffer.allocate(size64k, AllocationZone.Heap)
        asciiBuffer.position(0)
        asciiHeapBuffer.write(asciiBuffer)
        asciiHeapBuffer.resetForRead()
        asciiBuffer.position(0)

        // Mixed UTF-8 — alternating ASCII words and multibyte characters
        val mixedPattern = "Hello wor\u00E9The quick\u4E16brown fox\u00FCjumps ove\u754C"
        mixedUtf8Buffer = fillBufferWithPattern(mixedPattern, size64k)

        // Emoji-heavy — ASCII with frequent 4-byte emoji sequences
        val emojiPattern = "Hello \uD83D\uDE00World \uD83D\uDE80Test! \uD83C\uDF1F"
        emojiBuffer = fillBufferWithPattern(emojiPattern, size64k)

        // Chunked ASCII — various chunk sizes from the ASCII buffer
        asciiChunks = buildChunks(asciiBuffer, chunkSize)
        asciiChunks256 = buildChunks(asciiBuffer, 256)
        asciiChunks4k = buildChunks(asciiBuffer, 4096)
        asciiChunks16k = buildChunks(asciiBuffer, 16384)
    }

    @Benchmark
    fun asciiDecode(): Int {
        asciiBuffer.position(0)
        destination.clear()
        decoder.reset()
        val chars = decoder.decode(asciiBuffer, destination)
        decoder.finish(destination)
        return chars
    }

    @Benchmark
    fun mixedUtf8Decode(): Int {
        mixedUtf8Buffer.position(0)
        destination.clear()
        decoder.reset()
        val chars = decoder.decode(mixedUtf8Buffer, destination)
        decoder.finish(destination)
        return chars
    }

    @Benchmark
    fun emojiDecode(): Int {
        emojiBuffer.position(0)
        destination.clear()
        decoder.reset()
        val chars = decoder.decode(emojiBuffer, destination)
        decoder.finish(destination)
        return chars
    }

    @Benchmark
    fun chunkedStreamingAscii(): Int {
        destination.clear()
        decoder.reset()
        var totalChars = 0
        for (chunk in asciiChunks) {
            chunk.position(0)
            totalChars += decoder.decode(chunk, destination)
        }
        totalChars += decoder.finish(destination)
        return totalChars
    }

    @Benchmark
    fun baselineReadStringAscii(): Int {
        asciiBuffer.position(0)
        val str = asciiBuffer.readString(asciiBuffer.remaining(), Charset.UTF8)
        return str.length
    }

    // --- Bottleneck isolation benchmarks ---

    /** Heap buffer decode — if faster than Direct on Apple, the native->ByteArray copy is the bottleneck */
    @Benchmark
    fun asciiDecodeHeap(): Int {
        asciiHeapBuffer.position(0)
        destination.clear()
        decoder.reset()
        val chars = decoder.decode(asciiHeapBuffer, destination)
        decoder.finish(destination)
        return chars
    }

    /** Heap readString baseline — comparison point for Heap decode overhead */
    @Benchmark
    fun baselineReadStringAsciiHeap(): Int {
        asciiHeapBuffer.position(0)
        val str = asciiHeapBuffer.readString(asciiHeapBuffer.remaining(), Charset.UTF8)
        return str.length
    }

    /** 256B chunks (256 calls) — high per-call overhead if allocations dominate */
    @Benchmark
    fun chunkedStreaming256b(): Int {
        destination.clear()
        decoder.reset()
        var totalChars = 0
        for (chunk in asciiChunks256) {
            chunk.position(0)
            totalChars += decoder.decode(chunk, destination)
        }
        totalChars += decoder.finish(destination)
        return totalChars
    }

    /** 4KB chunks (16 calls) — moderate per-call overhead */
    @Benchmark
    fun chunkedStreaming4k(): Int {
        destination.clear()
        decoder.reset()
        var totalChars = 0
        for (chunk in asciiChunks4k) {
            chunk.position(0)
            totalChars += decoder.decode(chunk, destination)
        }
        totalChars += decoder.finish(destination)
        return totalChars
    }

    /** 16KB chunks (4 calls) — low per-call overhead */
    @Benchmark
    fun chunkedStreaming16k(): Int {
        destination.clear()
        decoder.reset()
        var totalChars = 0
        for (chunk in asciiChunks16k) {
            chunk.position(0)
            totalChars += decoder.decode(chunk, destination)
        }
        totalChars += decoder.finish(destination)
        return totalChars
    }

    companion object {
        private fun buildChunks(
            source: PlatformBuffer,
            chunkSize: Int,
        ): List<PlatformBuffer> {
            val total = source.limit()
            val savedLimit = source.limit()
            return (0 until total step chunkSize)
                .map { offset ->
                    val len = minOf(chunkSize, total - offset)
                    source.position(offset)
                    source.setLimit(offset + len)
                    val chunk = PlatformBuffer.allocate(len, AllocationZone.Direct)
                    chunk.write(source)
                    chunk.resetForRead()
                    chunk
                }.also {
                    source.setLimit(savedLimit)
                    source.position(0)
                }
        }

        private fun fillBufferWithPattern(
            pattern: String,
            targetSize: Int,
        ): PlatformBuffer {
            // Encode pattern once into a temporary buffer to get exact byte length
            val patternBuffer = PlatformBuffer.allocate(pattern.length * 4, AllocationZone.Direct)
            patternBuffer.writeString(pattern, Charset.UTF8)
            val patternByteLen = patternBuffer.position()
            patternBuffer.resetForRead()

            val buffer = PlatformBuffer.allocate(targetSize, AllocationZone.Direct)
            var written = 0
            while (written + patternByteLen <= targetSize) {
                patternBuffer.position(0)
                buffer.write(patternBuffer)
                written += patternByteLen
            }
            // Fill remaining bytes with ASCII to stay on valid UTF-8 boundary
            while (written < targetSize) {
                buffer.writeByte(0x20) // space
                written++
            }
            buffer.resetForRead()
            return buffer
        }
    }
}
