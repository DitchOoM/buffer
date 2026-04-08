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
        JsNodeStreamingCompressor(algorithm, level, windowBits, allocator)
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

/**
 * Stateful sync compressor backed by a persistent Node.js zlib Transform stream.
 * Uses [processSync] (zlib's internal _processChunk) for synchronous operation
 * while maintaining the LZ77 sliding window across flush calls (context takeover).
 *
 * State transitions:
 * - Created: stream is non-null, ready for compress/flush/finish
 * - After finish(): stream is destroyed and set to null — only reset() or close() are valid
 * - After close(): closed=true — all methods throw
 * - After reset(): fresh stream created, back to Created state
 */
private class JsNodeStreamingCompressor(
    private val algorithm: CompressionAlgorithm,
    private val level: CompressionLevel,
    private val windowBits: Int,
    override val allocator: BufferAllocator,
) : StreamingCompressor {
    private var stream: NodeTransformHandle? = createCompressStream(algorithm, level, windowBits)
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
        val s = stream ?: return
        val input = if (totalBytes == 0) emptyJsByteArray() else combineJsByteArrays(accumulatedChunks, totalBytes)
        accumulatedChunks.clear()
        totalBytes = 0
        val result =
            try {
                s.processSync(input, zlibSyncFlushFlag())
            } catch (e: Exception) {
                // zlib error — destroy stream to prevent use in corrupt state.
                // After this, only reset() or close() are valid.
                stream?.destroy()
                stream = null
                throw e
            }
        if (result.byteLength() > 0) {
            onOutput(result.toPlatformBuffer(allocator))
        }
    }

    override fun finish(onOutput: (ReadBuffer) -> Unit) {
        check(!closed) { "Compressor is closed" }
        val s = stream ?: return
        val input = if (totalBytes == 0) emptyJsByteArray() else combineJsByteArrays(accumulatedChunks, totalBytes)
        accumulatedChunks.clear()
        totalBytes = 0
        // Use _processChunk (one-shot) for finish — it handles the writeSync loop
        // correctly and destroys the C++ handle at the end, matching one-shot behavior.
        val result =
            try {
                s.processSyncOneShot(input, zlibFinishFlag())
            } catch (e: Exception) {
                stream?.destroy()
                stream = null
                throw e
            }
        stream = null // handle already destroyed by _processChunk
        if (result.byteLength() > 0) {
            onOutput(result.toPlatformBuffer(allocator))
        }
    }

    override fun reset() {
        stream?.destroy()
        accumulatedChunks.clear()
        totalBytes = 0
        stream = createCompressStream(algorithm, level, windowBits)
    }

    override fun close() {
        if (!closed) {
            stream?.destroy()
            stream = null
            accumulatedChunks.clear()
            closed = true
        }
    }
}

/**
 * Stateful sync decompressor backed by a persistent Node.js zlib Transform stream.
 * Maintains sliding window state across flush calls for context takeover.
 *
 * Same state machine as [JsNodeStreamingCompressor].
 */
private class JsNodeStreamingDecompressor(
    private val algorithm: CompressionAlgorithm,
    override val allocator: BufferAllocator,
) : StreamingDecompressor {
    private var stream: NodeTransformHandle? = createDecompressStream(algorithm)
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

    override fun flush(onOutput: (ReadBuffer) -> Unit) {
        check(!closed) { "Decompressor is closed" }
        val s = stream ?: return
        if (totalBytes == 0) return
        val combined = combineJsByteArrays(accumulatedChunks, totalBytes)
        accumulatedChunks.clear()
        totalBytes = 0
        val result =
            try {
                s.processSync(combined, zlibSyncFlushFlag())
            } catch (e: Exception) {
                stream?.destroy()
                stream = null
                throw e
            }
        if (result.byteLength() > 0) {
            onOutput(result.toPlatformBuffer(allocator))
        }
    }

    override fun finish(onOutput: (ReadBuffer) -> Unit) {
        check(!closed) { "Decompressor is closed" }
        val s = stream ?: return
        if (totalBytes == 0) return
        val combined = combineJsByteArrays(accumulatedChunks, totalBytes)
        accumulatedChunks.clear()
        totalBytes = 0
        // Use _processChunk (one-shot) for finish — handles Z_SYNC_FLUSH correctly
        // and destroys the C++ handle, matching the old inflateRawSync behavior.
        val result =
            try {
                s.processSyncOneShot(combined, zlibSyncFlushFlag())
            } catch (e: Exception) {
                stream?.destroy()
                stream = null
                throw e
            }
        stream = null // handle already destroyed by _processChunk
        if (result.byteLength() > 0) {
            onOutput(result.toPlatformBuffer(allocator))
        }
    }

    override fun reset() {
        stream?.destroy()
        accumulatedChunks.clear()
        totalBytes = 0
        stream = createDecompressStream(algorithm)
    }

    override fun close() {
        if (!closed) {
            stream?.destroy()
            stream = null
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
