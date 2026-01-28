package com.ditchoom.buffer.compression

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import kotlinx.coroutines.await
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

/**
 * JS streaming compressor factory.
 * Node.js: uses native zlib sync APIs.
 * Browser: throws UnsupportedOperationException (use SuspendingStreamingCompressor instead).
 */
actual fun StreamingCompressor.Companion.create(
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    allocator: BufferAllocator,
    outputBufferSize: Int,
): StreamingCompressor =
    if (isNodeJs) {
        JsNodeStreamingCompressor(algorithm, level, allocator)
    } else {
        throw UnsupportedOperationException(
            "Synchronous streaming compression not supported in browser. " +
                "Use SuspendingStreamingCompressor.create() instead.",
        )
    }

/**
 * JS streaming decompressor factory.
 * Node.js: uses native zlib sync APIs.
 * Browser: throws UnsupportedOperationException (use SuspendingStreamingDecompressor instead).
 */
actual fun StreamingDecompressor.Companion.create(
    algorithm: CompressionAlgorithm,
    allocator: BufferAllocator,
    outputBufferSize: Int,
    expectedSize: Int,
): StreamingDecompressor =
    if (isNodeJs) {
        JsNodeStreamingDecompressor(algorithm, allocator)
    } else {
        throw UnsupportedOperationException(
            "Synchronous streaming decompression not supported in browser. " +
                "Use SuspendingStreamingDecompressor.create() instead.",
        )
    }

/**
 * JS suspending streaming compressor factory.
 * Node.js: uses Transform stream API for stateful compression with flush support.
 * Browser: uses native CompressionStream API (no flush support).
 */
actual fun SuspendingStreamingCompressor.Companion.create(
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    allocator: BufferAllocator,
): SuspendingStreamingCompressor =
    if (isNodeJs) {
        // Node.js: use Transform stream for stateful compression
        NodeTransformStreamingCompressor(algorithm, level, allocator)
    } else {
        // Browser: use native CompressionStream
        BrowserStreamingCompressor(algorithm, allocator)
    }

/**
 * JS suspending streaming decompressor factory.
 * Node.js: uses Transform stream API for stateful decompression.
 * Browser: uses native DecompressionStream API.
 */
actual fun SuspendingStreamingDecompressor.Companion.create(
    algorithm: CompressionAlgorithm,
    allocator: BufferAllocator,
): SuspendingStreamingDecompressor =
    if (isNodeJs) {
        // Node.js: use Transform stream for stateful decompression
        NodeTransformStreamingDecompressor(algorithm, allocator)
    } else {
        // Browser: use native DecompressionStream
        BrowserStreamingDecompressor(algorithm, allocator)
    }

// ============================================================================
// Node.js sync streaming implementation
// ============================================================================

/**
 * Node.js streaming compressor using native zlib sync APIs.
 *
 * Note: This implementation accumulates chunks and compresses them all at once
 * on flush/finish. This is because Node.js zlib sync APIs don't maintain state
 * between calls. Each flush() produces independently compressed data, but the
 * compression ratio may be lower than true streaming compression.
 */
private class JsNodeStreamingCompressor(
    private val algorithm: CompressionAlgorithm,
    private val level: CompressionLevel,
    override val allocator: BufferAllocator,
) : StreamingCompressor {
    private val accumulatedChunks = mutableListOf<Int8Array>()
    private var totalBytes = 0
    private var closed = false

    override fun compress(
        input: ReadBuffer,
        onOutput: (ReadBuffer) -> Unit,
    ) {
        check(!closed) { "Compressor is closed" }

        val remaining = input.remaining()
        if (remaining > 0) {
            val chunk = input.toInt8Array()
            accumulatedChunks.add(chunk)
            totalBytes += remaining
        }
    }

    override fun flush(onOutput: (ReadBuffer) -> Unit) {
        check(!closed) { "Compressor is closed" }

        if (totalBytes == 0) {
            // Compress empty data with SYNC_FLUSH
            val result = compressWithSyncFlush(PlatformBuffer.allocate(0), algorithm, level)
            onOutput(result)
            return
        }

        val combined = Int8Array(totalBytes)
        var offset = 0
        for (chunk in accumulatedChunks) {
            combined.set(chunk, offset)
            offset += chunk.length
        }

        val buffer = combined.toJsBuffer()
        val result = compressWithSyncFlush(buffer, algorithm, level)
        onOutput(result)

        // Clear accumulated data - stream remains open
        accumulatedChunks.clear()
        totalBytes = 0
    }

    override fun finish(onOutput: (ReadBuffer) -> Unit) {
        check(!closed) { "Compressor is closed" }

        if (totalBytes == 0) {
            when (val result = compress(PlatformBuffer.allocate(0), algorithm, level)) {
                is CompressionResult.Success -> onOutput(result.buffer)
                is CompressionResult.Failure -> throw CompressionException(result.message, result.cause)
            }
            return
        }

        val combined = Int8Array(totalBytes)
        var offset = 0
        for (chunk in accumulatedChunks) {
            combined.set(chunk, offset)
            offset += chunk.length
        }

        val buffer = combined.toJsBuffer()
        when (val result = compress(buffer, algorithm, level)) {
            is CompressionResult.Success -> onOutput(result.buffer)
            is CompressionResult.Failure -> throw CompressionException(result.message, result.cause)
        }
    }

    override fun reset() {
        accumulatedChunks.clear()
        totalBytes = 0
    }

    override fun close() {
        if (!closed) {
            accumulatedChunks.clear()
            closed = true
        }
    }
}

/**
 * Node.js streaming decompressor using native zlib sync APIs.
 */
private class JsNodeStreamingDecompressor(
    private val algorithm: CompressionAlgorithm,
    override val allocator: BufferAllocator,
) : StreamingDecompressor {
    private val accumulatedChunks = mutableListOf<Int8Array>()
    private var totalBytes = 0
    private var closed = false

    override fun decompress(
        input: ReadBuffer,
        onOutput: (ReadBuffer) -> Unit,
    ) {
        check(!closed) { "Decompressor is closed" }

        val remaining = input.remaining()
        if (remaining > 0) {
            val chunk = input.toInt8Array()
            accumulatedChunks.add(chunk)
            totalBytes += remaining
        }
    }

    override fun finish(onOutput: (ReadBuffer) -> Unit) {
        check(!closed) { "Decompressor is closed" }

        if (totalBytes == 0) return

        val combined = Int8Array(totalBytes)
        var offset = 0
        for (chunk in accumulatedChunks) {
            combined.set(chunk, offset)
            offset += chunk.length
        }

        val buffer = combined.toJsBuffer()
        when (val result = decompress(buffer, algorithm)) {
            is CompressionResult.Success -> onOutput(result.buffer)
            is CompressionResult.Failure -> throw CompressionException(result.message, result.cause)
        }
    }

    override fun reset() {
        accumulatedChunks.clear()
        totalBytes = 0
    }

    override fun close() {
        if (!closed) {
            accumulatedChunks.clear()
            closed = true
        }
    }
}

// ============================================================================
// Browser CompressionStream/DecompressionStream implementation
// ============================================================================

/**
 * Get the compression format string for browser APIs.
 * Note: Browser only supports "gzip" and "deflate" (zlib format).
 * Raw deflate is not supported.
 */
private fun CompressionAlgorithm.toBrowserFormat(): String =
    when (this) {
        CompressionAlgorithm.Gzip -> "gzip"
        CompressionAlgorithm.Deflate -> "deflate"
        CompressionAlgorithm.Raw -> throw UnsupportedOperationException(
            "Raw deflate not supported in browser. Use Gzip or Deflate.",
        )
    }

/**
 * Browser streaming compressor using native CompressionStream API.
 * Accumulates input and compresses asynchronously on finish.
 */
private class BrowserStreamingCompressor(
    private val algorithm: CompressionAlgorithm,
    override val allocator: BufferAllocator,
) : SuspendingStreamingCompressor {
    private val accumulatedChunks = mutableListOf<Uint8Array>()
    private var totalBytes = 0
    private var closed = false

    override suspend fun compress(input: ReadBuffer): List<ReadBuffer> {
        check(!closed) { "Compressor is closed" }

        val remaining = input.remaining()
        if (remaining > 0) {
            val chunk = input.toUint8Array()
            accumulatedChunks.add(chunk)
            totalBytes += remaining
        }
        // CompressionStream buffers internally, no output until finish
        return emptyList()
    }

    override suspend fun flush(): List<ReadBuffer> =
        throw UnsupportedOperationException(
            "flush() not supported in browser. Browser CompressionStream does not support flush modes.",
        )

    override suspend fun finish(): List<ReadBuffer> {
        check(!closed) { "Compressor is closed" }

        if (totalBytes == 0) {
            // Compress empty data
            val compressed = compressWithBrowserStream(Uint8Array(0), algorithm)
            return listOf(compressed.toJsBuffer())
        }

        // Combine all chunks
        val combined = Uint8Array(totalBytes)
        var offset = 0
        for (chunk in accumulatedChunks) {
            combined.set(chunk, offset)
            offset += chunk.length
        }

        val compressed = compressWithBrowserStream(combined, algorithm)
        return listOf(compressed.toJsBuffer())
    }

    override fun reset() {
        accumulatedChunks.clear()
        totalBytes = 0
    }

    override fun close() {
        if (!closed) {
            accumulatedChunks.clear()
            closed = true
        }
    }
}

/**
 * Browser streaming decompressor using native DecompressionStream API.
 */
private class BrowserStreamingDecompressor(
    private val algorithm: CompressionAlgorithm,
    override val allocator: BufferAllocator,
) : SuspendingStreamingDecompressor {
    private val accumulatedChunks = mutableListOf<Uint8Array>()
    private var totalBytes = 0
    private var closed = false

    override suspend fun decompress(input: ReadBuffer): List<ReadBuffer> {
        check(!closed) { "Decompressor is closed" }

        val remaining = input.remaining()
        if (remaining > 0) {
            val chunk = input.toUint8Array()
            accumulatedChunks.add(chunk)
            totalBytes += remaining
        }
        return emptyList()
    }

    override suspend fun finish(): List<ReadBuffer> {
        check(!closed) { "Decompressor is closed" }

        if (totalBytes == 0) return emptyList()

        // Combine all chunks
        val combined = Uint8Array(totalBytes)
        var offset = 0
        for (chunk in accumulatedChunks) {
            combined.set(chunk, offset)
            offset += chunk.length
        }

        val decompressed = decompressWithBrowserStream(combined, algorithm)
        return listOf(decompressed.toJsBuffer())
    }

    override fun reset() {
        accumulatedChunks.clear()
        totalBytes = 0
    }

    override fun close() {
        if (!closed) {
            accumulatedChunks.clear()
            closed = true
        }
    }
}

/**
 * Compress data using browser's CompressionStream API.
 */
private suspend fun compressWithBrowserStream(
    data: Uint8Array,
    algorithm: CompressionAlgorithm,
): Uint8Array {
    val format = algorithm.toBrowserFormat()

    // Create CompressionStream and pipe data through it
    val cs = createCompressionStream(format)
    val blob = createBlob(data)
    val inputStream = getStream(blob)
    val compressedStream = pipeThrough(inputStream, cs)
    val response = createResponse(compressedStream)
    val arrayBuffer = getArrayBuffer(response).await()

    return createUint8ArrayFromBuffer(arrayBuffer)
}

/**
 * Decompress data using browser's DecompressionStream API.
 */
private suspend fun decompressWithBrowserStream(
    data: Uint8Array,
    algorithm: CompressionAlgorithm,
): Uint8Array {
    val format = algorithm.toBrowserFormat()

    // Create DecompressionStream and pipe data through it
    val ds = createDecompressionStream(format)
    val blob = createBlob(data)
    val inputStream = getStream(blob)
    val decompressedStream = pipeThrough(inputStream, ds)
    val response = createResponse(decompressedStream)
    val arrayBuffer = getArrayBuffer(response).await()

    return createUint8ArrayFromBuffer(arrayBuffer)
}

// Browser API wrappers
private fun createCompressionStream(format: String): dynamic = js("new CompressionStream(format)")

private fun createDecompressionStream(format: String): dynamic = js("new DecompressionStream(format)")

private fun createBlob(data: Uint8Array): dynamic = js("new Blob([data])")

private fun getStream(blob: dynamic): dynamic = blob.stream()

private fun pipeThrough(
    stream: dynamic,
    transform: dynamic,
): dynamic = stream.pipeThrough(transform)

private fun createResponse(stream: dynamic): dynamic = js("new Response(stream)")

private fun getArrayBuffer(response: dynamic): Promise<dynamic> = response.arrayBuffer().unsafeCast<Promise<dynamic>>()

private fun createUint8ArrayFromBuffer(buffer: dynamic): Uint8Array = js("new Uint8Array(buffer)").unsafeCast<Uint8Array>()

// ============================================================================
// Node.js Transform stream implementation (stateful flush)
// Uses pure JS helpers to encapsulate async operations in single Promises
// ============================================================================

/**
 * Pure JS helper that compresses data through a Transform stream and returns a Promise.
 * All stream operations are encapsulated in JS, avoiding runTest virtual time issues.
 */
private fun nodeCompressAsync(
    input: Uint8Array,
    algorithm: String,
    level: Int,
): Promise<Uint8Array> {
    val zlib = getNodeZlib()
    val options = js("{}")
    options["level"] = level

    val stream: dynamic =
        when (algorithm) {
            "gzip" -> zlib.createGzip(options)
            "deflate" -> zlib.createDeflate(options)
            else -> zlib.createDeflateRaw(options)
        }

    return Promise { resolve, reject ->
        val chunks = js("[]")

        stream.on("data") { chunk: dynamic ->
            chunks.push(chunk)
        }
        stream.on("error") { err: dynamic ->
            reject(Error(err.toString()))
        }
        stream.on("end") {
            val result: dynamic = js("Buffer").concat(chunks)
            val uint8 = Uint8Array(result.buffer, result.byteOffset, result.length)
            resolve(uint8)
        }
        stream.end(input)
    }
}

/**
 * Pure JS helper that decompresses data through a Transform stream and returns a Promise.
 */
private fun nodeDecompressAsync(
    input: Uint8Array,
    algorithm: String,
): Promise<Uint8Array> {
    val zlib = getNodeZlib()
    val options: dynamic = js("{}")
    if (algorithm == "raw") {
        options["finishFlush"] = zlib.constants.Z_SYNC_FLUSH
    }

    val stream: dynamic =
        when (algorithm) {
            "gzip" -> zlib.createGunzip(options)
            "deflate" -> zlib.createInflate(options)
            else -> zlib.createInflateRaw(options)
        }

    return Promise { resolve, reject ->
        val chunks = js("[]")

        stream.on("data") { chunk: dynamic ->
            chunks.push(chunk)
        }
        stream.on("error") { err: dynamic ->
            reject(Error(err.toString()))
        }
        stream.on("end") {
            val result: dynamic = js("Buffer").concat(chunks)
            val uint8 = Uint8Array(result.buffer, result.byteOffset, result.length)
            resolve(uint8)
        }
        stream.end(input)
    }
}

/**
 * Node.js streaming compressor using Transform stream API (zlib.createDeflate, etc).
 * Maintains compression dictionary across flush() calls for optimal compression.
 *
 * Uses pure JS async helpers for finish() to work correctly with runTest virtual time.
 */
private class NodeTransformStreamingCompressor(
    private val algorithm: CompressionAlgorithm,
    private val level: CompressionLevel,
    override val allocator: BufferAllocator,
) : SuspendingStreamingCompressor {
    private var stream: dynamic = null
    private var closed = false
    private val pendingChunks = mutableListOf<Uint8Array>()
    private var pendingBytes = 0

    init {
        initStream()
    }

    private fun initStream() {
        val zlib = getNodeZlib()
        val options = js("{}")
        options["level"] = level.value

        stream =
            when (algorithm) {
                CompressionAlgorithm.Gzip -> zlib.createGzip(options)
                CompressionAlgorithm.Deflate -> zlib.createDeflate(options)
                CompressionAlgorithm.Raw -> zlib.createDeflateRaw(options)
            }

        // Keep stream in paused mode (no 'data' listener = paused mode)
    }

    override suspend fun compress(input: ReadBuffer): List<ReadBuffer> {
        check(!closed) { "Compressor is closed" }

        val remaining = input.remaining()
        if (remaining == 0) return emptyList()

        // Accumulate chunks for later processing
        val chunk = input.toUint8Array()
        pendingChunks.add(chunk)
        pendingBytes += remaining

        return emptyList()
    }

    override suspend fun flush(): List<ReadBuffer> {
        check(!closed) { "Compressor is closed" }

        val zlib = getNodeZlib()
        val chunks = mutableListOf<ReadBuffer>()

        // Flush with Z_SYNC_FLUSH and collect output via 'readable' events
        Promise<Unit> { resolve, reject ->
            stream.once("error") { err: dynamic -> reject(Error(err.toString())) }
            stream.on("readable") {
                while (true) {
                    val chunk = stream.read()
                    if (chunk == null) break
                    chunks.add(chunk.unsafeCast<Uint8Array>().toJsBuffer())
                }
            }
            // Write pending chunks first
            for (chunk in pendingChunks) {
                stream.write(chunk)
            }
            pendingChunks.clear()
            pendingBytes = 0

            stream.flush(zlib.constants.Z_SYNC_FLUSH) {
                // Flush callback - all data should be available now
                resolve(Unit)
            }
        }.await()

        // Read any remaining data
        while (true) {
            val chunk = stream.read()
            if (chunk == null) break
            chunks.add(chunk.unsafeCast<Uint8Array>().toJsBuffer())
        }

        // Remove readable listener to avoid memory leak
        stream.removeAllListeners("readable")

        return chunks
    }

    override suspend fun finish(): List<ReadBuffer> {
        check(!closed) { "Compressor is closed" }

        // Combine all pending chunks
        val combined = Uint8Array(pendingBytes)
        var offset = 0
        for (chunk in pendingChunks) {
            combined.set(chunk, offset)
            offset += chunk.length
        }
        pendingChunks.clear()
        pendingBytes = 0

        // Destroy old stream and use pure JS helper
        stream?.destroy()
        stream = null

        val algString =
            when (algorithm) {
                CompressionAlgorithm.Gzip -> "gzip"
                CompressionAlgorithm.Deflate -> "deflate"
                CompressionAlgorithm.Raw -> "raw"
            }

        val result = nodeCompressAsync(combined, algString, level.value).await()
        return listOf(result.toJsBuffer())
    }

    override fun reset() {
        stream?.destroy()
        pendingChunks.clear()
        pendingBytes = 0
        initStream()
    }

    override fun close() {
        if (!closed) {
            stream?.destroy()
            stream = null
            pendingChunks.clear()
            closed = true
        }
    }
}

/**
 * Node.js streaming decompressor using Transform stream API.
 * Maintains decompression state across calls.
 *
 * Uses pure JS async helpers for finish() to work correctly with runTest virtual time.
 */
private class NodeTransformStreamingDecompressor(
    private val algorithm: CompressionAlgorithm,
    override val allocator: BufferAllocator,
) : SuspendingStreamingDecompressor {
    private var stream: dynamic = null
    private var closed = false
    private val pendingChunks = mutableListOf<Uint8Array>()
    private var pendingBytes = 0

    init {
        initStream()
    }

    private fun initStream() {
        val zlib = getNodeZlib()

        // Use Z_SYNC_FLUSH as finishFlush for raw deflate to handle sync-flushed data
        val options = js("{}")
        if (algorithm == CompressionAlgorithm.Raw) {
            options["finishFlush"] = zlib.constants.Z_SYNC_FLUSH
        }

        stream =
            when (algorithm) {
                CompressionAlgorithm.Gzip -> zlib.createGunzip(options)
                CompressionAlgorithm.Deflate -> zlib.createInflate(options)
                CompressionAlgorithm.Raw -> zlib.createInflateRaw(options)
            }

        // Keep stream in paused mode (no 'data' listener = paused mode)
    }

    override suspend fun decompress(input: ReadBuffer): List<ReadBuffer> {
        check(!closed) { "Decompressor is closed" }

        val remaining = input.remaining()
        if (remaining == 0) return emptyList()

        // Accumulate chunks for later processing
        val chunk = input.toUint8Array()
        pendingChunks.add(chunk)
        pendingBytes += remaining

        return emptyList()
    }

    override suspend fun finish(): List<ReadBuffer> {
        check(!closed) { "Decompressor is closed" }

        if (pendingBytes == 0) return emptyList()

        // Combine all pending chunks
        val combined = Uint8Array(pendingBytes)
        var offset = 0
        for (chunk in pendingChunks) {
            combined.set(chunk, offset)
            offset += chunk.length
        }
        pendingChunks.clear()
        pendingBytes = 0

        // Destroy old stream and use pure JS helper
        stream?.destroy()
        stream = null

        val algString =
            when (algorithm) {
                CompressionAlgorithm.Gzip -> "gzip"
                CompressionAlgorithm.Deflate -> "deflate"
                CompressionAlgorithm.Raw -> "raw"
            }

        val result = nodeDecompressAsync(combined, algString).await()
        return listOf(result.toJsBuffer())
    }

    override fun reset() {
        stream?.destroy()
        pendingChunks.clear()
        pendingBytes = 0
        initStream()
    }

    override fun close() {
        if (!closed) {
            stream?.destroy()
            stream = null
            pendingChunks.clear()
            closed = true
        }
    }
}
