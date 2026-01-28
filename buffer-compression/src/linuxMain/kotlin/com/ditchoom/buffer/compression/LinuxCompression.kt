package com.ditchoom.buffer.compression

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.ByteArrayBuffer
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.NativeBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
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
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.compressBound
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream

/**
 * Linux supports synchronous compression via system zlib.
 */
actual val supportsSyncCompression: Boolean = true
actual val supportsRawDeflate: Boolean = true
actual val supportsStatefulFlush: Boolean = true

/**
 * Helper to copy memory with platform-appropriate size_t conversion.
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
 */
@Suppress("NOTHING_TO_INLINE")
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private inline fun getCompressBound(size: Int): Int = compressBound(size.convert()).convert()

/**
 * Window bits for different compression formats.
 */
private const val WINDOW_BITS_ZLIB = 15
private const val WINDOW_BITS_RAW = -15
private const val WINDOW_BITS_GZIP = 31

/**
 * Linux implementation using system zlib with direct buffer access.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun compress(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
): CompressionResult =
    try {
        val remaining = buffer.remaining()
        if (remaining == 0) {
            CompressionResult.Success(createEmptyCompressed(algorithm))
        } else {
            val result = compressWithZStream(buffer, algorithm, level)
            CompressionResult.Success(result)
        }
    } catch (e: Exception) {
        CompressionResult.Failure("Compression failed: ${e.message}", e)
    }

@OptIn(ExperimentalForeignApi::class)
actual fun decompress(
    buffer: ReadBuffer,
    algorithm: CompressionAlgorithm,
): CompressionResult =
    try {
        val remaining = buffer.remaining()
        if (remaining == 0) {
            CompressionResult.Success(PlatformBuffer.allocate(0))
        } else {
            val result = decompressWithZStream(buffer, algorithm)
            CompressionResult.Success(result)
        }
    } catch (e: Exception) {
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
): PlatformBuffer {
    val inputSize = input.remaining()
    val inputPosition = input.position()

    // Allocate output buffer sized for worst case
    val maxOutputSize = getCompressBound(inputSize) + 32
    val output = NativeBuffer.allocate(maxOutputSize, ByteOrder.BIG_ENDIAN)
    val outputPtr = output.nativeAddress.toCPointer<ByteVar>()!!

    val s = nativeHeap.alloc<z_stream>()
    try {
        s.zalloc = null
        s.zfree = null
        s.opaque = null

        val windowBits =
            when (algorithm) {
                CompressionAlgorithm.Deflate -> WINDOW_BITS_ZLIB
                CompressionAlgorithm.Raw -> WINDOW_BITS_RAW
                CompressionAlgorithm.Gzip -> WINDOW_BITS_GZIP
            }

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
): PlatformBuffer {
    val inputSize = input.remaining()
    val inputPosition = input.position()

    val s = nativeHeap.alloc<z_stream>()
    try {
        s.zalloc = null
        s.zfree = null
        s.opaque = null

        val windowBits =
            when (algorithm) {
                CompressionAlgorithm.Deflate -> WINDOW_BITS_ZLIB
                CompressionAlgorithm.Raw -> WINDOW_BITS_RAW
                CompressionAlgorithm.Gzip -> WINDOW_BITS_GZIP
            }

        var result = inflateInit2(s.ptr, windowBits)

        if (result != Z_OK) {
            throw CompressionException("inflateInit2 failed with code: $result")
        }

        // Start with estimate, grow if needed
        var outputSize = maxOf(inputSize * 4, 1024)
        var output = NativeBuffer.allocate(outputSize, ByteOrder.BIG_ENDIAN)
        var outputPtr = output.nativeAddress.toCPointer<ByteVar>()!!

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
                    Z_OK, -5 -> {
                        if (s.avail_out > 0u && s.avail_in == 0u) {
                            // No more input and output space available means inflate
                            // can't make progress. Stream is complete even without
                            // BFINAL=1 (e.g., WebSocket per-message-deflate RFC 7692).
                            break
                        }
                        if (s.avail_out == 0u) {
                            // Need more output space - grow buffer
                            val newSize = outputSize * 2
                            val newOutput = NativeBuffer.allocate(newSize, ByteOrder.BIG_ENDIAN)
                            val newPtr = newOutput.nativeAddress.toCPointer<ByteVar>()!!
                            // Copy existing decompressed data to new buffer
                            copyMemory(newPtr, outputPtr, totalDecompressed)
                            output = newOutput
                            outputPtr = newPtr
                            outputSize = newSize
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
): R =
    when (buffer) {
        is NativeBuffer -> block(buffer.nativeAddress.toCPointer<ByteVar>()!!)
        is ByteArrayBuffer -> {
            val array = buffer.backingArray
            if (array.isEmpty()) {
                // Can't get addressOf(0) on empty array, but we also won't read from it
                // This case is handled at the caller level (remaining == 0 check)
                throw CompressionException("Cannot get pointer to empty buffer")
            }
            array.usePinned { pinned ->
                block(pinned.addressOf(0))
            }
        }
        else -> throw CompressionException("Unsupported buffer type for compression: ${buffer::class}")
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
    val buffer = PlatformBuffer.allocate(bytes.size, AllocationZone.Direct, ByteOrder.BIG_ENDIAN)
    buffer.writeBytes(bytes)
    buffer.resetForRead()
    return buffer
}
