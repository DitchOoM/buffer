package com.ditchoom.buffer.compression

import com.ditchoom.buffer.ReadBuffer

/**
 * Stateful streaming compressor that processes data incrementally.
 * Useful for compressing data that arrives in chunks (e.g., from network).
 *
 * Usage:
 * ```
 * val compressor = StreamingCompressor.create()
 * try {
 *     while (hasMoreData) {
 *         val chunk = receiveChunk()
 *         compressor.compress(chunk) { compressedChunk ->
 *             send(compressedChunk)
 *         }
 *     }
 *     compressor.finish { finalChunk ->
 *         send(finalChunk)
 *     }
 * } finally {
 *     compressor.close()
 * }
 * ```
 */
interface StreamingCompressor : AutoCloseable {
    /**
     * The buffer allocator used by this compressor.
     */
    val allocator: BufferAllocator

    /**
     * Compresses input data, invoking the callback for each output chunk.
     * May invoke callback zero or more times depending on buffering.
     *
     * @param input The input buffer to compress. Position is advanced.
     * @param onOutput Called with each compressed output chunk.
     */
    fun compress(
        input: ReadBuffer,
        onOutput: (ReadBuffer) -> Unit,
    )

    /**
     * Flushes buffered data using Z_SYNC_FLUSH, producing complete deflate blocks.
     * Unlike [finish], the stream remains open for more data.
     *
     * The output ends with the sync marker `00 00 FF FF` and can be immediately
     * decompressed without waiting for more data. Useful for protocols that need
     * independently decompressible messages within a single compression context.
     *
     * @param onOutput Called with flushed compressed data.
     */
    fun flush(onOutput: (ReadBuffer) -> Unit)

    /**
     * Finishes compression, flushing any buffered data.
     * Must be called after all input has been provided.
     *
     * @param onOutput Called with remaining compressed data.
     */
    fun finish(onOutput: (ReadBuffer) -> Unit)

    /**
     * Resets the compressor to initial state for reuse.
     * More efficient than creating a new compressor.
     */
    fun reset()

    companion object
}

/**
 * Stateful streaming decompressor that processes data incrementally.
 */
interface StreamingDecompressor : AutoCloseable {
    /**
     * The buffer allocator used by this decompressor.
     */
    val allocator: BufferAllocator

    /**
     * Decompresses input data, invoking the callback for each output chunk.
     *
     * @param input The compressed input buffer. Position is advanced.
     * @param onOutput Called with each decompressed output chunk.
     */
    fun decompress(
        input: ReadBuffer,
        onOutput: (ReadBuffer) -> Unit,
    )

    /**
     * Finishes decompression, validating completeness.
     *
     * @param onOutput Called with any remaining decompressed data.
     * @throws CompressionException if the stream is incomplete or invalid.
     */
    fun finish(onOutput: (ReadBuffer) -> Unit)

    /**
     * Emits any buffered partial output without finalizing the stream.
     *
     * Use this instead of [finish] when maintaining decompressor state across
     * multiple logical messages (e.g., WebSocket context takeover). Unlike
     * [finish], this does not signal end-of-stream and allows continued
     * decompression via [decompress].
     *
     * @param onOutput Called with any buffered decompressed data.
     */
    fun flush(onOutput: (ReadBuffer) -> Unit) {
        // Default implementation delegates to finish for backward compatibility.
        finish(onOutput)
    }

    /**
     * Resets the decompressor to initial state for reuse.
     */
    fun reset()

    companion object
}

/**
 * Convenience function that handles compress, finish, and close automatically.
 * All output chunks (from both compress and finish) go to the same callback.
 *
 * Usage:
 * ```
 * StreamingCompressor.create().use(onOutput = { send(it) }) { compress ->
 *     compress(chunk1)
 *     compress(chunk2)
 *     // finish() and close() called automatically
 * }
 * ```
 */
inline fun <R> StreamingCompressor.use(
    noinline onOutput: (ReadBuffer) -> Unit,
    block: (compress: (ReadBuffer) -> Unit) -> R,
): R {
    try {
        return block { input -> compress(input, onOutput) }
    } finally {
        finish(onOutput)
        close()
    }
}

/**
 * Convenience function that handles decompress, finish, and close automatically.
 */
inline fun <R> StreamingDecompressor.use(
    noinline onOutput: (ReadBuffer) -> Unit,
    block: (decompress: (ReadBuffer) -> Unit) -> R,
): R {
    try {
        return block { input -> decompress(input, onOutput) }
    } finally {
        finish(onOutput)
        close()
    }
}

/**
 * Suspending version of [use] for use with suspending I/O.
 * Uses the efficient synchronous compressor but allows suspend calls in the block.
 *
 * Usage with network I/O:
 * ```
 * StreamingCompressor.create().useSuspending(onOutput = { channel.send(it) }) { compress ->
 *     while (socket.hasData()) {
 *         val chunk = socket.read()  // suspend OK
 *         compress(chunk)
 *     }
 * }
 * ```
 */
suspend inline fun <R> StreamingCompressor.useSuspending(
    noinline onOutput: (ReadBuffer) -> Unit,
    block: suspend (compress: (ReadBuffer) -> Unit) -> R,
): R {
    try {
        return block { input -> compress(input, onOutput) }
    } finally {
        finish(onOutput)
        close()
    }
}

/**
 * Suspending version of [use] for use with suspending I/O.
 */
suspend inline fun <R> StreamingDecompressor.useSuspending(
    noinline onOutput: (ReadBuffer) -> Unit,
    block: suspend (decompress: (ReadBuffer) -> Unit) -> R,
): R {
    try {
        return block { input -> decompress(input, onOutput) }
    } finally {
        finish(onOutput)
        close()
    }
}

/**
 * Creates a streaming compressor.
 *
 * @param algorithm The compression algorithm to use.
 * @param level The compression level.
 * @param allocator Strategy for allocating output buffers.
 * @param outputBufferSize Size of output buffers (default 32KB).
 * @param windowBits The zlib window size (log2 of the LZ77 window size).
 *   When 0 (the default), uses the algorithm's default: 15 for Deflate/Zlib, -15 for Raw, 31 for Gzip.
 *   When non-zero, the value is passed directly to deflateInit2(). Valid range depends on the algorithm.
 *   Note: JVM's java.util.zip.Deflater does not support custom window sizes; this parameter is ignored on JVM.
 */
expect fun StreamingCompressor.Companion.create(
    algorithm: CompressionAlgorithm = CompressionAlgorithm.Deflate,
    level: CompressionLevel = CompressionLevel.Default,
    allocator: BufferAllocator = BufferAllocator.Default,
    outputBufferSize: Int = 32768,
    windowBits: Int = 0,
): StreamingCompressor

/**
 * Creates a streaming decompressor.
 *
 * @param algorithm The compression algorithm to use.
 * @param allocator Strategy for allocating output buffers.
 * @param outputBufferSize Size of output buffers (default 32KB).
 * @param expectedSize Optional hint for expected decompressed size. Used to pre-allocate buffers.
 */
expect fun StreamingDecompressor.Companion.create(
    algorithm: CompressionAlgorithm = CompressionAlgorithm.Deflate,
    allocator: BufferAllocator = BufferAllocator.Default,
    outputBufferSize: Int = 32768,
    expectedSize: Int = 0,
): StreamingDecompressor

// ============================================================================
// Suspending variants for async-only platforms (browser JS)
// ============================================================================

/**
 * Suspending streaming compressor for async-only platforms.
 * Browser JavaScript requires this variant since CompressionStream is async.
 *
 * Usage:
 * ```
 * val compressor = SuspendingStreamingCompressor.create()
 * try {
 *     while (hasMoreData) {
 *         val chunk = receiveChunk()
 *         compressor.compress(chunk).forEach { send(it) }
 *     }
 *     compressor.finish().forEach { send(it) }
 * } finally {
 *     compressor.close()
 * }
 * ```
 */
interface SuspendingStreamingCompressor : AutoCloseable {
    val allocator: BufferAllocator

    /**
     * Compresses input data, returning output chunks.
     * May return empty list if data is buffered.
     */
    suspend fun compress(input: ReadBuffer): List<ReadBuffer>

    /**
     * Flushes buffered data using Z_SYNC_FLUSH, producing complete deflate blocks.
     * Unlike [finish], the stream remains open for more data.
     *
     * The output ends with the sync marker `00 00 FF FF` and can be immediately
     * decompressed without waiting for more data.
     */
    suspend fun flush(): List<ReadBuffer>

    /**
     * Finishes compression, returning any remaining data.
     */
    suspend fun finish(): List<ReadBuffer>

    /**
     * Resets the compressor for reuse.
     */
    fun reset()

    companion object
}

/**
 * Suspending streaming decompressor for async-only platforms.
 */
interface SuspendingStreamingDecompressor : AutoCloseable {
    val allocator: BufferAllocator

    /**
     * Decompresses input data, returning output chunks.
     */
    suspend fun decompress(input: ReadBuffer): List<ReadBuffer>

    /**
     * Finishes decompression, returning any remaining data.
     */
    suspend fun finish(): List<ReadBuffer>

    /**
     * Emits any buffered partial output without finalizing the stream.
     * See [StreamingDecompressor.flush] for details.
     */
    suspend fun flush(): List<ReadBuffer> = finish()

    /**
     * Resets the decompressor for reuse.
     */
    fun reset()

    companion object
}

/**
 * Convenience function that handles compress, finish, and close automatically.
 * Returns all output chunks (from both compress and finish calls).
 *
 * Usage:
 * ```
 * val allChunks = SuspendingStreamingCompressor.create().use { compress ->
 *     compress(chunk1)
 *     compress(chunk2)
 *     // finish() and close() called automatically
 * }
 * ```
 */
suspend inline fun <R> SuspendingStreamingCompressor.use(block: (compress: suspend (ReadBuffer) -> List<ReadBuffer>) -> R): R {
    try {
        return block { input -> compress(input) }
    } finally {
        finish()
        close()
    }
}

/**
 * Convenience function that handles decompress, finish, and close automatically.
 */
suspend inline fun <R> SuspendingStreamingDecompressor.use(block: (decompress: suspend (ReadBuffer) -> List<ReadBuffer>) -> R): R {
    try {
        return block { input -> decompress(input) }
    } finally {
        finish()
        close()
    }
}

/**
 * Creates a suspending streaming compressor.
 * Required for browser JavaScript which only supports async CompressionStream.
 */
expect fun SuspendingStreamingCompressor.Companion.create(
    algorithm: CompressionAlgorithm = CompressionAlgorithm.Deflate,
    level: CompressionLevel = CompressionLevel.Default,
    allocator: BufferAllocator = BufferAllocator.Default,
): SuspendingStreamingCompressor

/**
 * Creates a suspending streaming decompressor.
 */
expect fun SuspendingStreamingDecompressor.Companion.create(
    algorithm: CompressionAlgorithm = CompressionAlgorithm.Deflate,
    allocator: BufferAllocator = BufferAllocator.Default,
): SuspendingStreamingDecompressor

// ============================================================================
// Common wrapper implementations for platforms with sync compression
// ============================================================================

/**
 * Wraps a synchronous [StreamingCompressor] to implement [SuspendingStreamingCompressor].
 * Used by platforms that have efficient sync compression (JVM, Android, Apple, Node.js).
 */
internal class SyncWrappingSuspendingCompressor(
    private val delegate: StreamingCompressor,
) : SuspendingStreamingCompressor {
    override val allocator: BufferAllocator get() = delegate.allocator

    override suspend fun compress(input: ReadBuffer): List<ReadBuffer> {
        val results = mutableListOf<ReadBuffer>()
        delegate.compress(input) { results.add(it) }
        return results
    }

    override suspend fun flush(): List<ReadBuffer> {
        val results = mutableListOf<ReadBuffer>()
        delegate.flush { results.add(it) }
        return results
    }

    override suspend fun finish(): List<ReadBuffer> {
        val results = mutableListOf<ReadBuffer>()
        delegate.finish { results.add(it) }
        return results
    }

    override fun reset() = delegate.reset()

    override fun close() = delegate.close()
}

/**
 * Wraps a synchronous [StreamingDecompressor] to implement [SuspendingStreamingDecompressor].
 * Used by platforms that have efficient sync decompression (JVM, Android, Apple, Node.js).
 */
internal class SyncWrappingSuspendingDecompressor(
    private val delegate: StreamingDecompressor,
) : SuspendingStreamingDecompressor {
    override val allocator: BufferAllocator get() = delegate.allocator

    override suspend fun decompress(input: ReadBuffer): List<ReadBuffer> {
        val results = mutableListOf<ReadBuffer>()
        delegate.decompress(input) { results.add(it) }
        return results
    }

    override suspend fun finish(): List<ReadBuffer> {
        val results = mutableListOf<ReadBuffer>()
        delegate.finish { results.add(it) }
        return results
    }

    override suspend fun flush(): List<ReadBuffer> {
        val results = mutableListOf<ReadBuffer>()
        delegate.flush { results.add(it) }
        return results
    }

    override fun reset() = delegate.reset()

    override fun close() = delegate.close()
}

// ============================================================================
// Gzip Format Utilities (shared across platforms)
// ============================================================================

/**
 * Gzip format constants used by platform implementations.
 */
internal object GzipFormat {
    /** Magic number identifying gzip format (0x1f 0x8b) */
    const val MAGIC: Int = 0x1f8b

    /** Fixed 10-byte header: magic(2) + method(1) + flags(1) + mtime(4) + xfl(1) + os(1) */
    const val HEADER_LONG: Long = 0x1f8b_0800_0000_0000L // magic + deflate + no flags + zero mtime
    const val HEADER_SHORT: Short = 0x00ff // xfl=0 + os=unknown (0xff)

    /** Flag bits (byte 3 of header) */
    const val FLAG_FHCRC: Int = 0x02 // Header CRC16 present
    const val FLAG_FEXTRA: Int = 0x04 // Extra field present
    const val FLAG_FNAME: Int = 0x08 // Original filename present
    const val FLAG_FCOMMENT: Int = 0x10 // Comment present
}

/**
 * Allocates a 10-byte gzip header buffer.
 */
internal fun BufferAllocator.allocateGzipHeader(): ReadBuffer {
    val header = allocate(10)
    header.writeLong(GzipFormat.HEADER_LONG)
    header.writeShort(GzipFormat.HEADER_SHORT)
    header.resetForRead()
    return header
}

/**
 * Computes gzip trailer as a Long (little-endian CRC32 + ISIZE).
 * The result can be written directly with writeLong().
 */
internal fun gzipTrailerLong(
    crc32: Int,
    size: Int,
): Long {
    // Gzip trailer is little-endian: CRC32 (4 bytes) followed by ISIZE (4 bytes)
    // Pack into Long with size in high bits, crc in low bits, then reverse for big-endian write
    val combined = ((size.toLong() and 0xFFFFFFFFL) shl 32) or (crc32.toLong() and 0xFFFFFFFFL)
    return reverseBytesLong(combined)
}

/**
 * Reverses the bytes in a Long value (big-endian to little-endian or vice versa).
 */
private fun reverseBytesLong(value: Long): Long =
    ((value and 0x00000000000000FFL) shl 56) or
        ((value and 0x000000000000FF00L) shl 40) or
        ((value and 0x0000000000FF0000L) shl 24) or
        ((value and 0x00000000FF000000L) shl 8) or
        ((value and 0x000000FF00000000L) ushr 8) or
        ((value and 0x0000FF0000000000L) ushr 24) or
        ((value and 0x00FF000000000000L) ushr 40) or
        ((value and 0xFF00000000000000UL.toLong()) ushr 56)
