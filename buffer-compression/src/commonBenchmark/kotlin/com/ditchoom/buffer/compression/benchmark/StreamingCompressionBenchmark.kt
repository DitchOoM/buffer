package com.ditchoom.buffer.compression.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.compression.CompressionAlgorithm
import com.ditchoom.buffer.compression.CompressionLevel
import com.ditchoom.buffer.compression.StreamingCompressor
import com.ditchoom.buffer.compression.StreamingDecompressor
import com.ditchoom.buffer.compression.compressScoped
import com.ditchoom.buffer.compression.create
import com.ditchoom.buffer.compression.decompressScoped
import com.ditchoom.buffer.compression.finishScoped
import com.ditchoom.buffer.compression.flushScoped
import com.ditchoom.buffer.managed
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
 * Benchmarks for streaming compression operations.
 * Measures per-message deflate overhead to identify JVM vs Linux gaps.
 *
 * Each benchmark simulates one WebSocket message compression cycle:
 * compress() + flush() + reset() or decompress() + finish() + reset().
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
open class StreamingCompressionBenchmark {
    private lateinit var compressor: StreamingCompressor
    private lateinit var decompressor: StreamingDecompressor

    // Input buffers at different sizes
    private lateinit var input16B: PlatformBuffer
    private lateinit var input4KB: PlatformBuffer
    private lateinit var input64KB: PlatformBuffer

    // Pre-compressed buffers for decompress benchmarks
    private lateinit var compressed16B: PlatformBuffer
    private lateinit var compressed4KB: PlatformBuffer
    private lateinit var compressed64KB: PlatformBuffer

    // Pre-decompressed buffers for string decode benchmarks
    private lateinit var decompressed4KB: PlatformBuffer
    private lateinit var decompressed64KB: PlatformBuffer

    @Setup
    fun setup() {
        compressor = StreamingCompressor.create(CompressionAlgorithm.Raw, CompressionLevel.Default, BufferFactory.Default)
        decompressor = StreamingDecompressor.create(CompressionAlgorithm.Raw, BufferFactory.Default)

        // Generate compressible text data (simulates WebSocket text messages)
        val text16B = "Hello, WebSocket!" // ~16 bytes
        val text4KB = buildCompressibleText(4 * 1024)
        val text64KB = buildCompressibleText(64 * 1024)

        input16B = textToDirectBuffer(text16B)
        input4KB = textToDirectBuffer(text4KB)
        input64KB = textToDirectBuffer(text64KB)

        // Pre-compress for decompress benchmarks
        compressed16B = compressForBenchmark(input16B)
        compressed4KB = compressForBenchmark(input4KB)
        compressed64KB = compressForBenchmark(input64KB)

        // Pre-decompress for string decode benchmarks
        decompressed4KB = decompressForBenchmark(compressed4KB)
        decompressed64KB = decompressForBenchmark(compressed64KB)
    }

    @TearDown
    fun tearDown() {
        compressor.close()
        decompressor.close()
    }

    // --- Compress + Flush + Reset (simulates one outgoing message) ---

    @Benchmark
    fun compressAndFlush16B(): Int {
        input16B.position(0)
        var totalBytes = 0
        compressor.compressScoped(input16B) { totalBytes += remaining() }
        compressor.flushScoped { totalBytes += remaining() }
        compressor.reset()
        return totalBytes
    }

    @Benchmark
    fun compressAndFlush4KB(): Int {
        input4KB.position(0)
        var totalBytes = 0
        compressor.compressScoped(input4KB) { totalBytes += remaining() }
        compressor.flushScoped { totalBytes += remaining() }
        compressor.reset()
        return totalBytes
    }

    @Benchmark
    fun compressAndFlush64KB(): Int {
        input64KB.position(0)
        var totalBytes = 0
        compressor.compressScoped(input64KB) { totalBytes += remaining() }
        compressor.flushScoped { totalBytes += remaining() }
        compressor.reset()
        return totalBytes
    }

    // --- Decompress + Finish + Reset (simulates one incoming message) ---

    @Benchmark
    fun decompressAndFinish16B(): Int {
        compressed16B.position(0)
        var totalBytes = 0
        decompressor.decompressScoped(compressed16B) { totalBytes += remaining() }
        decompressor.finishScoped { totalBytes += remaining() }
        decompressor.reset()
        return totalBytes
    }

    @Benchmark
    fun decompressAndFinish4KB(): Int {
        compressed4KB.position(0)
        var totalBytes = 0
        decompressor.decompressScoped(compressed4KB) { totalBytes += remaining() }
        decompressor.finishScoped { totalBytes += remaining() }
        decompressor.reset()
        return totalBytes
    }

    @Benchmark
    fun decompressAndFinish64KB(): Int {
        compressed64KB.position(0)
        var totalBytes = 0
        decompressor.decompressScoped(compressed64KB) { totalBytes += remaining() }
        decompressor.finishScoped { totalBytes += remaining() }
        decompressor.reset()
        return totalBytes
    }

    // --- Round-trip with String Decode (compress + decompress + readString) ---

    @Benchmark
    fun roundTripWithStringDecode4KB(): Int {
        // Compress
        input4KB.position(0)
        val compressedOutput = BufferFactory.managed().allocate(4 * 1024 + 256)
        compressor.compressScoped(input4KB) { compressedOutput.write(this) }
        compressor.flushScoped { compressedOutput.write(this) }
        compressor.reset()
        compressedOutput.resetForRead()

        // Decompress + String decode
        var totalChars = 0
        decompressor.decompressScoped(compressedOutput) {
            position(0)
            totalChars += readString(remaining(), Charset.UTF8).length
        }
        decompressor.finishScoped {
            position(0)
            totalChars += readString(remaining(), Charset.UTF8).length
        }
        decompressor.reset()

        return totalChars
    }

    @Benchmark
    fun roundTripWithStringDecode64KB(): Int {
        // Compress
        input64KB.position(0)
        val compressedOutput = BufferFactory.managed().allocate(64 * 1024 + 256)
        compressor.compressScoped(input64KB) { compressedOutput.write(this) }
        compressor.flushScoped { compressedOutput.write(this) }
        compressor.reset()
        compressedOutput.resetForRead()

        // Decompress + String decode
        var totalChars = 0
        decompressor.decompressScoped(compressedOutput) {
            position(0)
            totalChars += readString(remaining(), Charset.UTF8).length
        }
        decompressor.finishScoped {
            position(0)
            totalChars += readString(remaining(), Charset.UTF8).length
        }
        decompressor.reset()

        return totalChars
    }

    // --- Reset Only (isolate deflateReset/inflateReset overhead) ---

    @Benchmark
    fun resetOnlyCompressor(): Int {
        compressor.reset()
        return 1
    }

    @Benchmark
    fun resetOnlyDecompressor(): Int {
        decompressor.reset()
        return 1
    }

    // --- String Decode Only (isolate readString overhead) ---

    @Benchmark
    fun stringDecodeOnly4KB(): Int {
        decompressed4KB.position(0)
        return decompressed4KB.readString(decompressed4KB.remaining(), Charset.UTF8).length
    }

    @Benchmark
    fun stringDecodeOnly64KB(): Int {
        decompressed64KB.position(0)
        return decompressed64KB.readString(decompressed64KB.remaining(), Charset.UTF8).length
    }

    // --- Helpers ---

    private fun buildCompressibleText(targetBytes: Int): String {
        val sb = StringBuilder(targetBytes)
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
                "websocket",
                "compression",
                "benchmark",
                "test",
                "message",
                "payload",
                "streaming",
                "deflate",
                "inflate",
                "buffer",
            )
        var i = 0
        while (sb.length < targetBytes) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(words[i % words.size])
            i++
        }
        return sb.substring(0, targetBytes.coerceAtMost(sb.length))
    }

    private fun textToDirectBuffer(text: String): PlatformBuffer {
        val bytes = text.encodeToByteArray()
        val buffer = BufferFactory.Default.allocate(bytes.size)
        buffer.writeBytes(bytes)
        buffer.resetForRead()
        return buffer
    }

    private fun compressForBenchmark(input: PlatformBuffer): PlatformBuffer {
        input.position(0)
        val result = BufferFactory.managed().allocate(input.capacity + 256)
        compressor.compressScoped(input) { result.write(this) }
        compressor.flushScoped { result.write(this) }
        compressor.reset()
        result.resetForRead()
        return result
    }

    private fun decompressForBenchmark(compressed: PlatformBuffer): PlatformBuffer {
        compressed.position(0)
        val result = BufferFactory.managed().allocate(compressed.capacity * 10)
        decompressor.decompressScoped(compressed) { result.write(this) }
        decompressor.finishScoped { result.write(this) }
        decompressor.reset()
        result.resetForRead()
        return result
    }
}
