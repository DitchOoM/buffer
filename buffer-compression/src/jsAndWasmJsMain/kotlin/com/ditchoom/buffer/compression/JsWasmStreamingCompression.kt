package com.ditchoom.buffer.compression

import com.ditchoom.buffer.ReadBuffer

// Shared JS/wasmJs streaming compression factories and implementations.
// All JS interop is delegated to JsByteArray and the expect functions in JsInterop.kt.

// ============================================================================
// Factory functions (actual declarations for commonMain expects)
// ============================================================================

actual fun StreamingCompressor.Companion.create(
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    allocator: BufferAllocator,
    outputBufferSize: Int,
    windowBits: Int,
): StreamingCompressor =
    if (isNodeJs) {
        JsNodeStreamingCompressor(algorithm, level, allocator)
    } else {
        throw UnsupportedOperationException(
            "Synchronous streaming compression not supported in browser. " +
                "Use SuspendingStreamingCompressor.create() instead.",
        )
    }

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

actual fun SuspendingStreamingCompressor.Companion.create(
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    allocator: BufferAllocator,
): SuspendingStreamingCompressor =
    if (isNodeJs) {
        NodeTransformStreamingCompressor(algorithm, level, allocator)
    } else {
        BrowserStreamingCompressor(algorithm, allocator)
    }

actual fun SuspendingStreamingDecompressor.Companion.create(
    algorithm: CompressionAlgorithm,
    allocator: BufferAllocator,
): SuspendingStreamingDecompressor =
    if (isNodeJs) {
        NodeTransformStreamingDecompressor(algorithm, allocator)
    } else {
        BrowserStreamingDecompressor(algorithm, allocator)
    }

// ============================================================================
// Node.js sync streaming (accumulate chunks, compress on flush/finish)
// ============================================================================

private class JsNodeStreamingCompressor(
    private val algorithm: CompressionAlgorithm,
    private val level: CompressionLevel,
    override val allocator: BufferAllocator,
) : StreamingCompressor {
    private val accumulatedChunks = mutableListOf<JsByteArray>()
    private var totalBytes = 0
    private var closed = false

    override fun compress(
        input: ReadBuffer,
        onOutput: (ReadBuffer) -> Unit,
    ) {
        check(!closed) { "Compressor is closed" }
        val remaining = input.remaining()
        if (remaining > 0) {
            accumulatedChunks.add(input.toJsByteArray())
            totalBytes += remaining
        }
    }

    override fun flush(onOutput: (ReadBuffer) -> Unit) {
        check(!closed) { "Compressor is closed" }
        if (totalBytes == 0) {
            val result = nodeZlibSyncFlush(emptyJsByteArray(), algorithm, level)
            onOutput(result.toPlatformBuffer(allocator))
            return
        }
        val combined = combineJsByteArrays(accumulatedChunks, totalBytes)
        val result = nodeZlibSyncFlush(combined, algorithm, level)
        onOutput(result.toPlatformBuffer(allocator))
        accumulatedChunks.clear()
        totalBytes = 0
    }

    override fun finish(onOutput: (ReadBuffer) -> Unit) {
        check(!closed) { "Compressor is closed" }
        val input = if (totalBytes == 0) emptyJsByteArray() else combineJsByteArrays(accumulatedChunks, totalBytes)
        // Call nodeZlibSync directly — avoids allocating an intermediate pool buffer
        // that compress() would create via toPlatformBuffer then immediately consume.
        val compressed = nodeZlibSync(input, algorithm, level)
        onOutput(compressed.toPlatformBuffer(allocator))
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

private class JsNodeStreamingDecompressor(
    private val algorithm: CompressionAlgorithm,
    override val allocator: BufferAllocator,
) : StreamingDecompressor {
    private val accumulatedChunks = mutableListOf<JsByteArray>()
    private var totalBytes = 0
    private var closed = false

    override fun decompress(
        input: ReadBuffer,
        onOutput: (ReadBuffer) -> Unit,
    ) {
        check(!closed) { "Decompressor is closed" }
        val remaining = input.remaining()
        if (remaining > 0) {
            accumulatedChunks.add(input.toJsByteArray())
            totalBytes += remaining
        }
    }

    override fun finish(onOutput: (ReadBuffer) -> Unit) {
        check(!closed) { "Decompressor is closed" }
        if (totalBytes == 0) return
        val combined = combineJsByteArrays(accumulatedChunks, totalBytes)
        // Call nodeZlibDecompressSync directly — avoids intermediate pool buffer allocation.
        val decompressed = nodeZlibDecompressSync(combined, algorithm)
        onOutput(decompressed.toPlatformBuffer(allocator))
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
// Browser CompressionStream/DecompressionStream (async)
// ============================================================================

private class BrowserStreamingCompressor(
    private val algorithm: CompressionAlgorithm,
    override val allocator: BufferAllocator,
) : SuspendingStreamingCompressor {
    private val accumulatedChunks = mutableListOf<JsByteArray>()
    private var totalBytes = 0
    private var closed = false

    override suspend fun compress(input: ReadBuffer): List<ReadBuffer> {
        check(!closed) { "Compressor is closed" }
        val remaining = input.remaining()
        if (remaining > 0) {
            accumulatedChunks.add(input.toJsByteArray())
            totalBytes += remaining
        }
        return emptyList()
    }

    override suspend fun flush(): List<ReadBuffer> =
        throw UnsupportedOperationException(
            "flush() not supported in browser. Browser CompressionStream does not support flush modes.",
        )

    override suspend fun finish(): List<ReadBuffer> {
        check(!closed) { "Compressor is closed" }
        val input =
            if (totalBytes == 0) {
                emptyJsByteArray()
            } else {
                combineJsByteArrays(accumulatedChunks, totalBytes)
            }
        val compressed = browserCompress(input, algorithm)
        return listOf(compressed.toPlatformBuffer(allocator))
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

private class BrowserStreamingDecompressor(
    private val algorithm: CompressionAlgorithm,
    override val allocator: BufferAllocator,
) : SuspendingStreamingDecompressor {
    private val accumulatedChunks = mutableListOf<JsByteArray>()
    private var totalBytes = 0
    private var closed = false

    override suspend fun decompress(input: ReadBuffer): List<ReadBuffer> {
        check(!closed) { "Decompressor is closed" }
        val remaining = input.remaining()
        if (remaining > 0) {
            accumulatedChunks.add(input.toJsByteArray())
            totalBytes += remaining
        }
        return emptyList()
    }

    override suspend fun finish(): List<ReadBuffer> {
        check(!closed) { "Decompressor is closed" }
        if (totalBytes == 0) return emptyList()
        val combined = combineJsByteArrays(accumulatedChunks, totalBytes)
        val decompressed = browserDecompress(combined, algorithm)
        return listOf(decompressed.toPlatformBuffer(allocator))
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
// Node.js Transform stream (async, stateful flush)
// ============================================================================

private class NodeTransformStreamingCompressor(
    private val algorithm: CompressionAlgorithm,
    private val level: CompressionLevel,
    override val allocator: BufferAllocator,
) : SuspendingStreamingCompressor {
    private var stream: NodeTransformHandle? = null
    private var closed = false
    private val pendingChunks = mutableListOf<JsByteArray>()

    init {
        initStream()
    }

    private fun initStream() {
        stream = createCompressStream(algorithm, level)
    }

    override suspend fun compress(input: ReadBuffer): List<ReadBuffer> {
        check(!closed) { "Compressor is closed" }
        if (input.remaining() == 0) return emptyList()
        pendingChunks.add(input.toJsByteArray())
        return emptyList()
    }

    override suspend fun flush(): List<ReadBuffer> {
        check(!closed) { "Compressor is closed" }
        val s = stream ?: return emptyList()
        val chunks = pendingChunks.toList()
        pendingChunks.clear()
        val output = s.writeAndFlush(chunks)
        return output.map { it.toPlatformBuffer(allocator) }
    }

    override suspend fun finish(): List<ReadBuffer> {
        check(!closed) { "Compressor is closed" }
        val s = stream ?: return emptyList()
        val chunks = pendingChunks.toList()
        pendingChunks.clear()
        val result = s.writeAndEnd(chunks)
        stream = null
        return if (result.byteLength() > 0) listOf(result.toPlatformBuffer(allocator)) else emptyList()
    }

    override fun reset() {
        stream?.destroy()
        pendingChunks.clear()
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

private class NodeTransformStreamingDecompressor(
    private val algorithm: CompressionAlgorithm,
    override val allocator: BufferAllocator,
) : SuspendingStreamingDecompressor {
    private var closed = false
    private val pendingChunks = mutableListOf<JsByteArray>()

    override suspend fun decompress(input: ReadBuffer): List<ReadBuffer> {
        check(!closed) { "Decompressor is closed" }
        if (input.remaining() == 0) return emptyList()
        pendingChunks.add(input.toJsByteArray())
        return emptyList()
    }

    override suspend fun finish(): List<ReadBuffer> {
        check(!closed) { "Decompressor is closed" }
        if (pendingChunks.isEmpty()) return emptyList()
        val chunks = pendingChunks.toList()
        pendingChunks.clear()
        val result = nodeTransformDecompressOneShot(chunks, algorithm)
        return listOf(result.toPlatformBuffer(allocator))
    }

    override fun reset() {
        pendingChunks.clear()
    }

    override fun close() {
        if (!closed) {
            pendingChunks.clear()
            closed = true
        }
    }
}
