package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

/**
 * Compression algorithm types.
 */
sealed interface CompressionAlgorithm {
    data object Deflate : CompressionAlgorithm

    data object Gzip : CompressionAlgorithm

    data object Raw : CompressionAlgorithm // Raw deflate without headers
}

/**
 * Compression level.
 */
sealed interface CompressionLevel {
    val value: Int

    data object NoCompression : CompressionLevel {
        override val value = 0
    }

    data object BestSpeed : CompressionLevel {
        override val value = 1
    }

    data object Default : CompressionLevel {
        override val value = 6
    }

    data object BestCompression : CompressionLevel {
        override val value = 9
    }

    data class Custom(
        override val value: Int,
    ) : CompressionLevel {
        init {
            require(value in MIN_LEVEL..MAX_LEVEL) {
                "Compression level must be between $MIN_LEVEL and $MAX_LEVEL"
            }
        }
    }

    companion object {
        /** Lowest zlib compression level (no compression). */
        const val MIN_LEVEL: Int = 0

        /** Highest zlib compression level (best compression). */
        const val MAX_LEVEL: Int = 9
    }
}

/**
 * Result of compression/decompression operations.
 */
sealed interface CompressionResult {
    data class Success(
        val buffer: PlatformBuffer,
    ) : CompressionResult

    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : CompressionResult
}

/**
 * Compresses data from a ReadBuffer using the specified algorithm.
 * Reads from current position to limit.
 *
 * @param dictionary Optional preset dictionary (see [supportsPresetDictionary]). Consumed
 *   fully if provided. A small dictionary tuned to the corpus's common byte sequences
 *   improves compression of many small, structurally-similar messages (MQTT, WebSocket
 *   frames, telemetry) at no runtime cost — smaller output compresses faster too.
 *   [CompressionAlgorithm.Gzip] never supports a dictionary (see [CompressionAlgorithm.supportsDictionary]);
 *   passing one throws [CompressionException].
 */
expect fun compress(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm = CompressionAlgorithm.Deflate,
    level: CompressionLevel = CompressionLevel.Default,
    dictionary: ReadBuffer? = null,
): CompressionResult

/**
 * Decompresses data from a ReadBuffer using the specified algorithm.
 * Reads from current position to limit.
 *
 * @param dictionary The same preset dictionary passed to [compress]. Consumed fully if
 *   provided. Must match what the encoder used, or decompression fails/produces garbage.
 */
expect fun decompress(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm = CompressionAlgorithm.Deflate,
    dictionary: ReadBuffer? = null,
): CompressionResult

/**
 * Extension function to get buffer from CompressionResult, throwing on failure.
 */
fun CompressionResult.getOrThrow(): PlatformBuffer =
    when (this) {
        is CompressionResult.Success -> buffer
        is CompressionResult.Failure -> throw CompressionException(message, cause)
    }

/**
 * Extension function to get buffer from CompressionResult, returning null on failure.
 */
fun CompressionResult.getOrNull(): PlatformBuffer? =
    when (this) {
        is CompressionResult.Success -> buffer
        is CompressionResult.Failure -> null
    }

/**
 * Exception thrown when compression/decompression fails.
 */
class CompressionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Whether this platform supports the synchronous [compress] and [decompress] functions.
 *
 * - JVM, Android, Apple: `true` - native zlib available
 * - JS (Node.js): `true` - sync zlib module available
 * - JS (Browser): `false` - only async CompressionStream API, use [SuspendingStreamingCompressor]
 */
expect val supportsSyncCompression: Boolean

/**
 * Whether the current platform supports raw deflate (no zlib/gzip headers).
 *
 * - JVM, Android, Apple: `true`
 * - JS (Node.js): `true` - uses native zlib inflateRaw
 * - JS (Browser): `false` - CompressionStream API only supports "gzip" and "deflate"
 */
expect val supportsRawDeflate: Boolean

/**
 * Whether the sync [StreamingCompressor] maintains compression state across flush() calls.
 *
 * When true, calling [StreamingCompressor.flush] produces output that can be
 * immediately decompressed, and the compressor can continue accepting more data
 * for the same compression context.
 *
 * When false, flush() produces independent compressed blocks and clears internal state.
 *
 * - JVM, Android, Apple: `true` - true zlib z_stream maintains state
 * - JS/Wasm (Node.js): `true` - Node's zlib bindings maintain state across flush
 * - JS/Wasm (Browser): `false` - CompressionStream doesn't support flush
 */
expect val supportsStatefulFlush: Boolean

/**
 * Whether the current platform's [StreamingCompressor.create] honors a
 * non-default [WindowBits] argument.
 *
 * - Linux native: `true` - native zlib `deflateInit2` accepts the windowBits.
 * - Apple: `true` - native zlib `deflateInit2` accepts the windowBits.
 * - JS/Wasm (Node.js): `true` - Node's zlib options thread `windowBits` through.
 * - JVM, Android: `false` - `java.util.zip.Deflater` does not expose a window-size
 *   parameter; the value is silently ignored and the deflater always uses the
 *   algorithm default (15-bit / 32 KB window).
 * - JS/Wasm (Browser): `false` - the CompressionStream Web API has no window-size
 *   parameter; the value is silently ignored.
 *
 * Round-trip tests pass on all platforms because both compressor and decompressor
 * fall back to the algorithm default when [WindowBits] is ignored. Use this flag
 * to gate assertions that expect a specific window size to be reflected in the
 * encoded output.
 */
expect val supportsCustomWindowBits: Boolean

/**
 * Whether this platform's compression backend exposes a preset-dictionary knob at all,
 * independent of algorithm.
 *
 * - JVM, Android, Apple, Linux: `true` - `Deflater`/`Inflater`/zlib all expose
 *   `setDictionary`.
 * - JS (Node.js), Wasm (Node.js): `true` - Node's zlib bindings accept a `dictionary`
 *   option.
 * - JS (Browser), Wasm (Browser): `false` - the CompressionStream/DecompressionStream
 *   Web API has no dictionary parameter.
 *
 * Use [CompressionAlgorithm.supportsDictionary] rather than this flag directly — it also
 * accounts for [CompressionAlgorithm.Gzip], which never supports a dictionary regardless
 * of platform.
 */
expect val supportsPresetDictionary: Boolean

/**
 * Whether this [CompressionAlgorithm] can use a preset dictionary on the current platform.
 *
 * [CompressionAlgorithm.Gzip] is always `false`: RFC 1952 has no preset-dictionary
 * mechanism at the format level. This holds even on platforms whose gzip implementation
 * happens to be built on a raw deflate stream under the hood (see the JVM/Android
 * implementation notes on [supportsPresetDictionary]'s call sites) — the restriction is
 * about wire-format interop, not a particular backend's internals.
 *
 * [CompressionAlgorithm.Deflate] and [CompressionAlgorithm.Raw] follow
 * [supportsPresetDictionary].
 *
 * Passing a dictionary when this is `false` throws [CompressionException] rather than
 * silently ignoring it: unlike window bits (where both sides fall back to the same safe
 * default), a dictionary applied on only one side of a compress/decompress pair corrupts
 * the stream.
 */
fun CompressionAlgorithm.supportsDictionary(): Boolean = this != CompressionAlgorithm.Gzip && supportsPresetDictionary

/**
 * Validates that [dictionary] is only supplied when [algorithm] actually supports one.
 * Called by every platform backend before wiring a dictionary into the underlying
 * compressor/decompressor, so an unsupported combination fails loudly instead of being
 * silently dropped (see [CompressionAlgorithm.supportsDictionary] for why silent
 * dropping is unsafe here).
 */
internal fun requireDictionarySupport(
    algorithm: CompressionAlgorithm,
    dictionary: ReadBuffer?,
) {
    if (dictionary == null || algorithm.supportsDictionary()) return
    val reason =
        if (algorithm == CompressionAlgorithm.Gzip) {
            "Gzip does not support preset dictionaries (RFC 1952 has no preset-dictionary mechanism)"
        } else {
            "This platform's compression backend does not support preset dictionaries"
        }
    throw CompressionException(reason)
}

// =============================================================================
// Suspending One-Shot API (works on all platforms including browser JS)
// =============================================================================

/**
 * Compresses data using the specified algorithm. Works on all platforms.
 *
 * This is a convenience function that handles the streaming API internally.
 * For large data or when you need to process chunks incrementally, use
 * [SuspendingStreamingCompressor] directly.
 *
 * @param buffer The data to compress (reads from position to limit). **Fully consumed after call.**
 * @param algorithm The compression algorithm to use
 * @param level The compression level
 * @param factory The buffer factory for the output buffer
 * @param dictionary Optional preset dictionary, consumed fully if provided. See
 *   [CompressionAlgorithm.supportsDictionary].
 * @return The compressed data as a single buffer (position=0, limit=compressed size)
 */
suspend fun compressAsync(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm = CompressionAlgorithm.Gzip,
    level: CompressionLevel = CompressionLevel.Default,
    factory: BufferFactory = BufferFactory.Default,
    dictionary: ReadBuffer? = null,
): PlatformBuffer {
    val compressor = SuspendingStreamingCompressor.create(algorithm, level, dictionary = dictionary)
    return try {
        val output = mutableListOf<ReadBuffer>()
        output += compressor.compress(buffer)
        output += compressor.finish()
        combineBuffers(output, factory)
    } finally {
        compressor.close()
    }
}

/**
 * Decompresses data using the specified algorithm. Works on all platforms.
 *
 * This is a convenience function that handles the streaming API internally.
 * For large data or when you need to process chunks incrementally, use
 * [SuspendingStreamingDecompressor] directly.
 *
 * @param buffer The compressed data (reads from position to limit). Position is advanced.
 * @param algorithm The compression algorithm to use
 * @param factory The buffer factory for the output buffer
 * @param expectedOutputSize Hint for pre-allocating the output buffer. If the actual
 *   decompressed size exceeds this, the buffer grows automatically. Use 0 (default)
 *   if unknown. Providing a good estimate reduces memory allocations.
 * @param dictionary The same preset dictionary passed to [compressAsync], consumed fully
 *   if provided. See [CompressionAlgorithm.supportsDictionary].
 * @return The decompressed data as a single buffer (position=0, limit=decompressed size)
 */
suspend fun decompressAsync(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm = CompressionAlgorithm.Gzip,
    factory: BufferFactory = BufferFactory.Default,
    expectedOutputSize: Int = 0,
    dictionary: ReadBuffer? = null,
): PlatformBuffer {
    val decompressor = SuspendingStreamingDecompressor.create(algorithm, dictionary = dictionary)
    return try {
        if (expectedOutputSize > 0) {
            // Optimized path: write directly to pre-allocated buffer
            decompressToBuffer(decompressor, buffer, factory, expectedOutputSize)
        } else {
            // Default path: collect chunks then combine
            val output = mutableListOf<ReadBuffer>()
            output += decompressor.decompress(buffer)
            output += decompressor.finish()
            combineBuffers(output, factory)
        }
    } finally {
        decompressor.close()
    }
}

/**
 * Decompresses directly to a pre-allocated buffer, growing if needed.
 * Returns a buffer sliced to exact size (no wasted capacity).
 */
private suspend fun decompressToBuffer(
    decompressor: SuspendingStreamingDecompressor,
    input: ReadBuffer,
    factory: BufferFactory,
    expectedSize: Int,
): PlatformBuffer {
    var output = factory.allocate(expectedSize)
    var capacity = expectedSize // Track capacity since it's not exposed in API

    // Process decompression chunks
    for (chunk in decompressor.decompress(input)) {
        val result = ensureCapacityAndWrite(output, capacity, chunk, factory)
        output = result.first
        capacity = result.second
    }

    // Process finish chunks
    for (chunk in decompressor.finish()) {
        val result = ensureCapacityAndWrite(output, capacity, chunk, factory)
        output = result.first
        capacity = result.second
    }

    // Slice to exact size if we have excess capacity
    val actualSize = output.position()
    return if (actualSize == capacity) {
        output.resetForRead()
        output
    } else {
        // Return a right-sized buffer to avoid memory waste
        output.position(0)
        output.setLimit(actualSize)
        val result = factory.allocate(actualSize)
        result.write(output)
        result.resetForRead()
        result
    }
}

/**
 * Ensures the output buffer has capacity for the chunk, growing if needed.
 * Returns the (possibly new) output buffer and its capacity.
 */
private fun ensureCapacityAndWrite(
    output: PlatformBuffer,
    capacity: Int,
    chunk: ReadBuffer,
    factory: BufferFactory,
): Pair<PlatformBuffer, Int> {
    val needed = chunk.remaining()
    val available = capacity - output.position()

    return if (needed <= available) {
        output.write(chunk)
        Pair(output, capacity)
    } else {
        // Grow buffer: double size or add needed space, whichever is larger
        val newCapacity = maxOf(capacity * 2, capacity + needed)
        val newOutput = factory.allocate(newCapacity)

        // Copy existing data
        val written = output.position()
        output.position(0)
        output.setLimit(written)
        newOutput.write(output)

        // Write new chunk
        newOutput.write(chunk)
        Pair(newOutput, newCapacity)
    }
}

/**
 * Combines multiple buffers into a single buffer.
 */
internal fun combineBuffers(
    buffers: List<ReadBuffer>,
    factory: BufferFactory = BufferFactory.Default,
): PlatformBuffer {
    val totalSize = buffers.sumOf { it.remaining() }
    val result = factory.allocate(totalSize)
    for (buf in buffers) {
        result.write(buf)
    }
    result.resetForRead()
    return result
}

// ============================================================================
// Deflate Format Utilities
// ============================================================================

/**
 * Deflate format constants.
 */
object DeflateFormat {
    /**
     * Z_SYNC_FLUSH marker: `00 00 FF FF`
     *
     * This 4-byte sequence is produced by deflate's Z_SYNC_FLUSH operation and marks
     * the end of a complete deflate block. Data up to this marker can be decompressed
     * independently without waiting for more input.
     *
     * The sequence represents an empty non-final stored block in the deflate format.
     */
    const val SYNC_FLUSH_MARKER: Int = 0x0000FFFF

    /** Length in bytes of the [SYNC_FLUSH_MARKER] (`00 00 FF FF`). */
    const val SYNC_FLUSH_MARKER_BYTES: Int = 4
}

/**
 * Strips the Z_SYNC_FLUSH marker (`00 00 FF FF`) from the end of compressed data.
 *
 * When using [SuspendingStreamingCompressor.flush] or [StreamingCompressor.flush],
 * the output ends with this 4-byte sync marker. Some protocols (like WebSocket
 * permessage-deflate) require stripping this marker before transmission.
 *
 * @return The buffer with the marker removed by adjusting the limit. If the marker
 *   is not present, returns the buffer unchanged.
 */
fun ReadBuffer.stripSyncFlushMarker(): ReadBuffer {
    if (remaining() < DeflateFormat.SYNC_FLUSH_MARKER_BYTES) return this

    val markerStart = limit() - DeflateFormat.SYNC_FLUSH_MARKER_BYTES
    val lastFourBytes = getInt(markerStart)

    // Sync marker bytes are 00 00 FF FF. When read as int:
    // - Big-endian: 0x0000FFFF
    // - Little-endian: 0xFFFF0000
    val expectedMarker =
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            DeflateFormat.SYNC_FLUSH_MARKER
        } else {
            0xFFFF0000.toInt()
        }

    if (lastFourBytes == expectedMarker) {
        setLimit(markerStart)
    }
    return this
}

/**
 * Compresses data using Z_SYNC_FLUSH and strips the sync marker.
 *
 * This is a convenience function for protocols that need independently decompressible
 * messages without the trailing sync marker (e.g., WebSocket permessage-deflate).
 *
 * The compressed output can be decompressed using [decompressWithSyncFlush].
 *
 * @param buffer The data to compress.
 * @param level The compression level.
 * @param factory The buffer factory for the output buffer.
 * @return Compressed data with the sync marker stripped.
 */
suspend fun compressWithSyncFlush(
    buffer: ReadBuffer,
    level: CompressionLevel = CompressionLevel.Default,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    val compressor = SuspendingStreamingCompressor.create(CompressionAlgorithm.Raw, level)
    val chunks = mutableListOf<ReadBuffer>()
    try {
        chunks.addAll(compressor.compress(buffer))
        chunks.addAll(compressor.flush())
    } finally {
        compressor.close()
    }

    val compressed = combineBuffers(chunks, factory)
    return compressed.stripSyncFlushMarker()
}

/**
 * Decompresses data that was compressed with [compressWithSyncFlush].
 *
 * Automatically appends the sync marker before decompression without copying the input buffer.
 *
 * @param buffer The compressed data (without sync marker).
 * @param factory The buffer factory for the output buffer.
 * @return The decompressed data.
 */
suspend fun decompressWithSyncFlush(
    buffer: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    val decompressor = SuspendingStreamingDecompressor.create(CompressionAlgorithm.Raw)
    return try {
        val output = mutableListOf<ReadBuffer>()
        output += decompressor.decompress(buffer)
        // Write just the 4-byte marker without copying the input buffer
        val marker = factory.allocate(4)
        marker.writeInt(DeflateFormat.SYNC_FLUSH_MARKER)
        marker.resetForRead()
        output += decompressor.decompress(marker)
        output += decompressor.finish()
        combineBuffers(output, factory)
    } finally {
        decompressor.close()
    }
}
