package com.ditchoom.buffer.compression

import com.ditchoom.buffer.ReadBuffer

// ============================================================================
// Platform detection
// ============================================================================

/** Whether running in a Node.js environment (vs browser). */
internal expect val isNodeJs: Boolean

// ============================================================================
// Opaque byte array handle — hides Int8Array (JS) vs JsAny (wasmJs)
// ============================================================================

internal expect class JsByteArray

/** Get the byte length of a [JsByteArray]. */
internal expect fun JsByteArray.byteLength(): Int

/**
 * Convert a [ReadBuffer] to a [JsByteArray] copy, consuming remaining bytes.
 * Safe for async operations — the copy won't be invalidated by memory growth.
 */
internal expect fun ReadBuffer.toJsByteArray(): JsByteArray

/**
 * Convert a [ReadBuffer] to a [JsByteArray] view, consuming remaining bytes.
 * Zero-copy on both JS (subarray) and wasmJs (view on linear memory).
 *
 * **Only safe for synchronous consumption** — on wasmJs, the view is invalidated
 * if `memory.grow()` is called (which can happen during any Kotlin allocation).
 * Use [toJsByteArray] for async paths.
 */
internal expect fun ReadBuffer.toJsByteArrayView(): JsByteArray

/** Combine multiple [JsByteArray]s into a single one. */
internal expect fun combineJsByteArrays(
    arrays: List<JsByteArray>,
    totalSize: Int,
): JsByteArray

/** Create an empty [JsByteArray] of size 0. */
internal expect fun emptyJsByteArray(): JsByteArray

/** Convert a [JsByteArray] to a [ReadBuffer] ready for reading (position=0, limit=length). */
internal expect fun JsByteArray.toPlatformBuffer(allocator: BufferAllocator = BufferAllocator.Default): ReadBuffer

// ============================================================================
// Node.js sync zlib operations
// ============================================================================

/** Synchronous Node.js zlib compression. */
internal expect fun nodeZlibSync(
    input: JsByteArray,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): JsByteArray

/** Synchronous Node.js zlib compression with Z_SYNC_FLUSH. */
internal expect fun nodeZlibSyncFlush(
    input: JsByteArray,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): JsByteArray

/** Synchronous Node.js zlib decompression. */
internal expect fun nodeZlibDecompressSync(
    input: JsByteArray,
    algorithm: CompressionAlgorithm,
): JsByteArray

// ============================================================================
// Browser CompressionStream / DecompressionStream (async)
// ============================================================================

/** Compress via browser CompressionStream API. */
internal expect suspend fun browserCompress(
    input: JsByteArray,
    algorithm: CompressionAlgorithm,
): JsByteArray

/** Decompress via browser DecompressionStream API. */
internal expect suspend fun browserDecompress(
    input: JsByteArray,
    algorithm: CompressionAlgorithm,
): JsByteArray

// ============================================================================
// Node.js Transform stream operations (async, stateful)
// ============================================================================

/** Opaque handle to a Node.js zlib Transform stream. */
internal expect class NodeTransformHandle

/** Create a compression Transform stream. */
internal expect fun createCompressStream(
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): NodeTransformHandle

/** Create a decompression Transform stream. */
internal expect fun createDecompressStream(algorithm: CompressionAlgorithm): NodeTransformHandle

/** Write chunks and flush with Z_SYNC_FLUSH. Returns output chunks. */
internal expect suspend fun NodeTransformHandle.writeAndFlush(inputs: List<JsByteArray>): List<JsByteArray>

/** Write chunks and end the stream. Returns the combined output. */
internal expect suspend fun NodeTransformHandle.writeAndEnd(inputs: List<JsByteArray>): JsByteArray

/** Destroy the Transform stream. */
internal expect fun NodeTransformHandle.destroy()

// ============================================================================
// One-shot async Transform stream (stateless — creates and destroys stream)
// ============================================================================

/** Compress through a one-shot Transform stream. */
internal expect suspend fun nodeTransformCompressOneShot(
    inputs: List<JsByteArray>,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): JsByteArray

/** Decompress through a one-shot Transform stream. */
internal expect suspend fun nodeTransformDecompressOneShot(
    inputs: List<JsByteArray>,
    algorithm: CompressionAlgorithm,
): JsByteArray
