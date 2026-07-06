package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.MutableDataBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managedMemoryAccess
import com.ditchoom.buffer.nativeMemoryAccess
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawPtr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NEED_DICT
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.compressBound
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2
import platform.zlib.deflateSetDictionary
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.inflateSetDictionary
import platform.zlib.z_stream

/**
 * zlib's `Z_BUF_ERROR` (-5): inflate made no progress because the output buffer is
 * full or input was exhausted. Treated as "needs more output space", not a failure.
 */
private const val Z_BUF_ERROR_CODE = -5

/**
 * Apple supports synchronous compression via system zlib.
 */
actual val supportsSyncCompression: Boolean = true
actual val supportsRawDeflate: Boolean = true
actual val supportsStatefulFlush: Boolean = true

// Apple system zlib's deflateInit2 honors the windowBits argument; threaded through
// AppleZlibStreamingCompressor.
actual val supportsCustomWindowBits: Boolean = true

// Apple system zlib exposes deflateSetDictionary/inflateSetDictionary directly.
actual val supportsPresetDictionary: Boolean = true

/**
 * Materializes an optional dictionary [ReadBuffer] into an owned [PlatformBuffer], consuming
 * the source buffer fully (the "consume once, retain internally" contract on the streaming
 * `create()` factories). No native-pointer resolution happens here — that's done per-call by
 * [withBufferPointer], since a managed buffer's backing array is only safely dereferenced
 * while pinned for that call.
 */
internal fun BufferFactory.materializeDictionary(dictionary: ReadBuffer?): PlatformBuffer? {
    if (dictionary == null) return null
    val owned = allocate(dictionary.remaining())
    owned.write(dictionary)
    owned.resetForRead()
    return owned
}

/**
 * Applies [dictionary] via `deflateSetDictionary`. Must be called immediately after
 * `deflateInit2`/`deflateReset`, before any `deflate()` call (zlib-wrapped), or before any
 * `deflate()` call / at a block boundary (raw) — see the zlib manual on `deflateSetDictionary`.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal fun applyDeflateDictionary(
    stream: CPointer<z_stream>,
    dictionary: ReadBuffer,
) {
    val result =
        withBufferPointer(dictionary) { ptr ->
            deflateSetDictionary(stream, ptr.reinterpret(), dictionary.remaining().convert())
        }
    if (result != Z_OK) {
        throw CompressionException("deflateSetDictionary failed with code: $result")
    }
}

/**
 * Applies [dictionary] via `inflateSetDictionary`. For raw inflate this can be called any
 * time before the data needing it; for zlib-wrapped streams it must be called right after an
 * `inflate()` call returns [Z_NEED_DICT] — see the zlib manual on `inflateSetDictionary`.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal fun applyInflateDictionary(
    stream: CPointer<z_stream>,
    dictionary: ReadBuffer,
): Int =
    withBufferPointer(dictionary) { ptr ->
        inflateSetDictionary(stream, ptr.reinterpret(), dictionary.remaining().convert())
    }

/**
 * Helper to copy memory with platform-appropriate size_t conversion.
 * Uses UnsafeNumber to handle different bit widths across Apple platforms
 * (arm64 uses 64-bit size_t, arm64_32 like watchOS uses 32-bit size_t).
 */
@Suppress("NOTHING_TO_INLINE")
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private inline fun copyMemory(
    dst: CPointer<ByteVar>?,
    src: CPointer<ByteVar>?,
    size: Int,
) {
    memcpy(dst, src, size.convert())
}

/**
 * Helper to get compress bound with platform-appropriate size_t conversion.
 * Uses UnsafeNumber to handle different bit widths across Apple platforms.
 */
@Suppress("NOTHING_TO_INLINE")
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private inline fun getCompressBound(size: Int): Int = compressBound(size.convert()).convert()

/**
 * Apple implementation using system zlib with direct buffer access.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun compress(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    dictionary: ReadBuffer?,
): CompressionResult =
    try {
        requireDictionarySupport(algorithm, dictionary)
        val remaining = buffer.remaining()
        if (remaining == 0) {
            CompressionResult.Success(createEmptyCompressed(algorithm))
        } else {
            val result = compressWithZStream(buffer, algorithm, level, dictionary)
            CompressionResult.Success(result)
        }
    } catch (e: CompressionException) {
        CompressionResult.Failure("Compression failed: ${e.message}", e)
    } catch (e: IllegalStateException) {
        CompressionResult.Failure("Compression failed: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        CompressionResult.Failure("Compression failed: ${e.message}", e)
    }

@OptIn(ExperimentalForeignApi::class)
actual fun decompress(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm,
    dictionary: ReadBuffer?,
): CompressionResult =
    try {
        requireDictionarySupport(algorithm, dictionary)
        val remaining = buffer.remaining()
        if (remaining == 0) {
            CompressionResult.Success(BufferFactory.Default.allocate(0))
        } else {
            val result = decompressWithZStream(buffer, algorithm, dictionary)
            CompressionResult.Success(result)
        }
    } catch (e: CompressionException) {
        CompressionResult.Failure("Decompression failed: ${e.message}", e)
    } catch (e: IllegalStateException) {
        CompressionResult.Failure("Decompression failed: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        CompressionResult.Failure("Decompression failed: ${e.message}", e)
    }

/**
 * Compress buffer using z_stream for direct buffer access.
 * No memory copying - writes directly to output buffer.
 */
@OptIn(ExperimentalForeignApi::class)
private fun compressWithZStream(
    input: ReadBuffer,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    dictionary: ReadBuffer?,
): PlatformBuffer {
    val inputSize = input.remaining()
    val inputPosition = input.position()

    // Allocate output buffer sized for worst case
    val maxOutputSize = getCompressBound(inputSize) + 32
    val output = BufferFactory.Default.allocate(maxOutputSize) as MutableDataBuffer

    @Suppress("UNCHECKED_CAST")
    val outputPtr = output.data.mutableBytes as CPointer<ByteVar>

    val s = nativeHeap.alloc<z_stream>()
    try {
        s.zalloc = null
        s.zfree = null
        s.opaque = null

        val windowBits = resolveWindowBits(algorithm, WindowBits.Default)

        var result =
            deflateInit2(
                s.ptr,
                level.value,
                Z_DEFLATED,
                windowBits,
                8,
                0,
            )

        if (result != Z_OK) {
            throw CompressionException("deflateInit2 failed with code: $result")
        }

        // Must be set immediately after init, before the first deflate() call.
        dictionary?.let { applyDeflateDictionary(s.ptr, it) }

        withBufferPointer(input) { inputPtr ->
            s.next_in = (inputPtr + inputPosition)?.reinterpret()
            s.avail_in = inputSize.convert()
            s.next_out = outputPtr.reinterpret()
            s.avail_out = maxOutputSize.convert()

            result = deflate(s.ptr, Z_FINISH)
        }
        deflateEnd(s.ptr)

        if (result != Z_STREAM_END) {
            throw CompressionException("deflate failed with code: $result")
        }

        val compressedSize = maxOutputSize - s.avail_out.toInt()

        // Advance input position
        input.position(inputPosition + inputSize)

        // Set position and limit - no copy needed
        output.position(compressedSize)
        output.resetForRead()
        return output
    } finally {
        nativeHeap.free(s.rawPtr)
    }
}

/**
 * Decompress buffer using z_stream for direct buffer access.
 */
@OptIn(ExperimentalForeignApi::class)
private fun decompressWithZStream(
    input: ReadBuffer,
    algorithm: CompressionAlgorithm,
    dictionary: ReadBuffer?,
): PlatformBuffer {
    val inputSize = input.remaining()
    val inputPosition = input.position()

    val s = nativeHeap.alloc<z_stream>()
    try {
        s.zalloc = null
        s.zfree = null
        s.opaque = null

        val windowBits = resolveWindowBits(algorithm, WindowBits.Default)

        var result = inflateInit2(s.ptr, windowBits)

        if (result != Z_OK) {
            throw CompressionException("inflateInit2 failed with code: $result")
        }

        // Raw inflate has no in-band Z_NEED_DICT signal, so the dictionary must be applied
        // eagerly. Zlib-wrapped streams signal via Z_NEED_DICT during the inflate loop below.
        if (algorithm == CompressionAlgorithm.Raw) {
            dictionary?.let { applyInflateDictionary(s.ptr, it) }
        }

        // Start with estimate, grow if needed
        var outputSize = maxOf(inputSize * 4, 1024)
        var output = BufferFactory.Default.allocate(outputSize) as MutableDataBuffer

        @Suppress("UNCHECKED_CAST")
        var outputPtr = output.data.mutableBytes as CPointer<ByteVar>

        var totalDecompressed = 0

        withBufferPointer(input) { inputPtr ->
            s.next_in = (inputPtr + inputPosition)?.reinterpret()
            s.avail_in = inputSize.convert()

            while (true) {
                s.next_out = (outputPtr + totalDecompressed)?.reinterpret()
                s.avail_out = (outputSize - totalDecompressed).convert()

                result = inflate(s.ptr, Z_FINISH)

                totalDecompressed = outputSize - s.avail_out.toInt()

                when (result) {
                    Z_STREAM_END -> break
                    Z_OK, Z_BUF_ERROR_CODE -> {
                        if (s.avail_out > 0u && s.avail_in == 0u) {
                            // No more input and output space available means inflate
                            // can't make progress. Stream is complete even without
                            // BFINAL=1 (e.g., WebSocket per-message-deflate RFC 7692).
                            break
                        }
                        if (s.avail_out == 0u) {
                            // Need more output space - grow buffer
                            val newSize = outputSize * 2
                            val newOutput = BufferFactory.Default.allocate(newSize) as MutableDataBuffer

                            @Suppress("UNCHECKED_CAST")
                            val newPtr = newOutput.data.mutableBytes as CPointer<ByteVar>
                            // Copy existing decompressed data to new buffer
                            copyMemory(newPtr, outputPtr, totalDecompressed)
                            output = newOutput
                            outputPtr = newPtr
                            outputSize = newSize
                        }
                    }
                    Z_NEED_DICT -> {
                        if (dictionary == null) {
                            inflateEnd(s.ptr)
                            throw CompressionException("Dictionary required")
                        }
                        val setResult = applyInflateDictionary(s.ptr, dictionary)
                        if (setResult != Z_OK) {
                            inflateEnd(s.ptr)
                            throw CompressionException("inflateSetDictionary failed with code: $setResult")
                        }
                    }
                    else -> {
                        inflateEnd(s.ptr)
                        throw CompressionException("inflate failed with code: $result")
                    }
                }
            }
        }

        inflateEnd(s.ptr)

        // Advance input position
        input.position(inputPosition + inputSize - s.avail_in.toInt())

        // Set position and limit - no final copy needed
        output.position(totalDecompressed)
        output.resetForRead()
        return output
    } finally {
        nativeHeap.free(s.rawPtr)
    }
}

/**
 * Execute a block with a pointer to the buffer's data.
 * Handles pinning for ByteArrayBuffer to ensure the pointer remains valid.
 */
@OptIn(ExperimentalForeignApi::class)
private inline fun <R> withBufferPointer(
    buffer: ReadBuffer,
    block: (CPointer<ByteVar>) -> R,
): R {
    val nativeAccess = buffer.nativeMemoryAccess
    if (nativeAccess != null) {
        return block(nativeAccess.nativeAddress.toCPointer<ByteVar>()!!)
    }
    val managedAccess = buffer.managedMemoryAccess
    if (managedAccess != null) {
        return managedAccess.backingArray.usePinned { pinned ->
            block(pinned.addressOf(managedAccess.arrayOffset))
        }
    }
    throw CompressionException("Unsupported buffer type for compression: ${buffer::class}")
}

/**
 * Create empty compressed data for different formats.
 */
private fun createEmptyCompressed(algorithm: CompressionAlgorithm): PlatformBuffer {
    val bytes =
        when (algorithm) {
            CompressionAlgorithm.Deflate -> {
                // Empty zlib stream
                byteArrayOf(0x78, 0x01, 0x01, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x00, 0x01)
            }
            CompressionAlgorithm.Raw -> {
                // Empty raw deflate
                byteArrayOf(0x01, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte())
            }
            CompressionAlgorithm.Gzip -> {
                // Empty gzip: header + empty deflate + trailer (CRC=0, size=0)
                byteArrayOf(
                    0x1F,
                    0x8B.toByte(),
                    0x08,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0xFF.toByte(),
                    0x03,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                )
            }
        }
    val buffer = BufferFactory.Default.allocate(bytes.size, ByteOrder.BIG_ENDIAN)
    buffer.writeBytes(bytes)
    buffer.resetForRead()
    return buffer
}
