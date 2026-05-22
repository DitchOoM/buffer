package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer

// Shared JS/wasmJs streaming compression factories and implementations.
// All JS interop is delegated to JsByteArray and the expect functions in JsInterop.kt.

// ============================================================================
// Factory functions (actual declarations for commonMain expects)
// ============================================================================

actual fun StreamingCompressor.Companion.create(
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    bufferFactory: BufferFactory,
    outputBufferSize: Int,
    windowBits: WindowBits,
): StreamingCompressor =
    if (isNodeJs) {
        JsNodeStreamingCompressor(algorithm, level, bufferFactory, windowBits)
    } else {
        throw UnsupportedOperationException(
            "Synchronous streaming compression not supported in browser. " +
                "Use SuspendingStreamingCompressor.create() instead.",
        )
    }

actual fun StreamingDecompressor.Companion.create(
    algorithm: CompressionAlgorithm,
    bufferFactory: BufferFactory,
    outputBufferSize: Int,
    expectedSize: Int,
): StreamingDecompressor =
    if (isNodeJs) {
        JsNodeStreamingDecompressor(algorithm, bufferFactory)
    } else {
        throw UnsupportedOperationException(
            "Synchronous streaming decompression not supported in browser. " +
                "Use SuspendingStreamingDecompressor.create() instead.",
        )
    }

actual fun SuspendingStreamingCompressor.Companion.create(
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    bufferFactory: BufferFactory,
): SuspendingStreamingCompressor =
    if (isNodeJs) {
        NodeTransformStreamingCompressor(algorithm, level, bufferFactory)
    } else {
        BrowserStreamingCompressor(algorithm, bufferFactory)
    }

actual fun SuspendingStreamingDecompressor.Companion.create(
    algorithm: CompressionAlgorithm,
    bufferFactory: BufferFactory,
): SuspendingStreamingDecompressor =
    if (isNodeJs) {
        NodeTransformStreamingDecompressor(algorithm, bufferFactory)
    } else {
        BrowserStreamingDecompressor(algorithm, bufferFactory)
    }

// ============================================================================
// Node.js sync streaming (accumulate chunks, compress on flush/finish)
// ============================================================================

/**
 * Stateful sync compressor backed by a persistent Node.js zlib Transform stream.
 *
 * Uses the C++ handle's `writeSync()` directly (via [processSync]) for [flush] so the
 * LZ77 sliding window is preserved across calls — required for WebSocket
 * permessage-deflate with `client_no_context_takeover` disabled. [finish] takes the
 * destroy-on-completion path via [processSyncOneShot], matching the old one-shot
 * `deflateSync` behavior.
 *
 * State machine:
 * - Created: `stream` non-null; compress/flush/finish all valid.
 * - After [finish]: `stream` is null (handle destroyed inside `_processChunk`); only
 *   [reset] or [close] are valid.
 * - After [reset]: fresh stream; back to Created.
 * - After [close]: terminal; all operations throw.
 */
private class JsNodeStreamingCompressor(
    private val algorithm: CompressionAlgorithm,
    private val level: CompressionLevel,
    override val bufferFactory: BufferFactory,
    private val customWindowBits: WindowBits,
) : StreamingCompressor {
    private var stream: NodeTransformHandle? = createCompressStream(algorithm, level, customWindowBits)
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
        val result = s.processSync(input, zlibSyncFlushFlag())
        if (result.byteLength() > 0) {
            onOutput(result.toPlatformBuffer(bufferFactory))
        }
    }

    override fun finish(onOutput: (ReadBuffer) -> Unit) {
        check(!closed) { "Compressor is closed" }
        val s = stream ?: return
        val input = if (totalBytes == 0) emptyJsByteArray() else combineJsByteArrays(accumulatedChunks, totalBytes)
        accumulatedChunks.clear()
        totalBytes = 0
        val result = s.processSyncOneShot(input, zlibFinishFlag())
        stream = null
        if (result.byteLength() > 0) {
            onOutput(result.toPlatformBuffer(bufferFactory))
        }
    }

    override fun reset() {
        accumulatedChunks.clear()
        totalBytes = 0
        // In-place handle reset (deflateReset) avoids destroying and reallocating the
        // C++ zlib handle on every message — critical under `no_context_takeover`
        // where the websocket layer calls reset() per message. Only allocate a fresh
        // stream if `finish()` already destroyed it (`stream == null`).
        val s = stream
        if (s != null) {
            s.resetState()
        } else {
            stream = createCompressStream(algorithm, level, customWindowBits)
        }
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
 * Same state machine as [JsNodeStreamingCompressor]; see that class for details.
 */
private class JsNodeStreamingDecompressor(
    private val algorithm: CompressionAlgorithm,
    override val bufferFactory: BufferFactory,
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
        val result = s.processSync(combined, zlibSyncFlushFlag())
        if (result.byteLength() > 0) {
            onOutput(result.toPlatformBuffer(bufferFactory))
        }
    }

    override fun finish(onOutput: (ReadBuffer) -> Unit) {
        check(!closed) { "Decompressor is closed" }
        val s = stream ?: return
        if (totalBytes == 0) return
        val combined = combineJsByteArrays(accumulatedChunks, totalBytes)
        accumulatedChunks.clear()
        totalBytes = 0
        // Z_SYNC_FLUSH (not Z_FINISH) — raw inflate has no end-of-stream marker,
        // and `_processChunk` destroys the handle regardless.
        val result = s.processSyncOneShot(combined, zlibSyncFlushFlag())
        stream = null
        if (result.byteLength() > 0) {
            onOutput(result.toPlatformBuffer(bufferFactory))
        }
    }

    override fun reset() {
        accumulatedChunks.clear()
        totalBytes = 0
        // Cheap in-place inflateReset — see JsNodeStreamingCompressor.reset for rationale.
        val s = stream
        if (s != null) {
            s.resetState()
        } else {
            stream = createDecompressStream(algorithm)
        }
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
    override val bufferFactory: BufferFactory,
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
        return listOf(compressed.toPlatformBuffer(bufferFactory))
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
    override val bufferFactory: BufferFactory,
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
        return listOf(decompressed.toPlatformBuffer(bufferFactory))
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
    override val bufferFactory: BufferFactory,
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
        return output.map { it.toPlatformBuffer(bufferFactory) }
    }

    override suspend fun finish(): List<ReadBuffer> {
        check(!closed) { "Compressor is closed" }
        val s = stream ?: return emptyList()
        val chunks = pendingChunks.toList()
        pendingChunks.clear()
        val result = s.writeAndEnd(chunks)
        stream = null
        return if (result.byteLength() > 0) listOf(result.toPlatformBuffer(bufferFactory)) else emptyList()
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
    override val bufferFactory: BufferFactory,
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
        return listOf(result.toPlatformBuffer(bufferFactory))
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
