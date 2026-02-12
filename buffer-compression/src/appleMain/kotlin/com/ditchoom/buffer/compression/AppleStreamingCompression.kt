@file:Suppress("UNCHECKED_CAST")

package com.ditchoom.buffer.compression

import com.ditchoom.buffer.ByteArrayBuffer
import com.ditchoom.buffer.MutableDataBuffer
import com.ditchoom.buffer.MutableDataBufferSlice
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadWriteBuffer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawPtr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.Z_SYNC_FLUSH
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream

/**
 * Apple streaming compressor factory using z_stream for true incremental compression.
 */
actual fun StreamingCompressor.Companion.create(
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    allocator: BufferAllocator,
    outputBufferSize: Int,
    windowBits: Int,
): StreamingCompressor = AppleZlibStreamingCompressor(algorithm, level, allocator, outputBufferSize)

/**
 * Apple streaming decompressor factory using z_stream for true incremental decompression.
 */
actual fun StreamingDecompressor.Companion.create(
    algorithm: CompressionAlgorithm,
    allocator: BufferAllocator,
    outputBufferSize: Int,
    expectedSize: Int,
): StreamingDecompressor = AppleZlibStreamingDecompressor(algorithm, allocator, outputBufferSize)

/**
 * Window bits for different compression formats:
 * - 15: zlib format (default)
 * - -15: raw deflate (no header/trailer)
 * - 15 + 16 = 31: gzip format
 */
private const val WINDOW_BITS_ZLIB = 15
private const val WINDOW_BITS_RAW = -15
private const val WINDOW_BITS_GZIP = 31

/**
 * Apple streaming compressor using zlib z_stream for true incremental compression.
 * Zero-copy: writes directly to output buffers via native pointers.
 * Supports all allocator types including pool-allocated buffers.
 */
@OptIn(ExperimentalForeignApi::class)
private class AppleZlibStreamingCompressor(
    private val algorithm: CompressionAlgorithm,
    private val level: CompressionLevel,
    override val allocator: BufferAllocator,
    private val outputBufferSize: Int,
) : StreamingCompressor {
    private var streamPtr: CPointer<z_stream>? = null
    private var closed = false

    init {
        initStream()
    }

    private fun initStream() {
        val s = nativeHeap.alloc<z_stream>()
        s.zalloc = null
        s.zfree = null
        s.opaque = null
        s.next_in = null
        s.avail_in = 0u
        s.next_out = null
        s.avail_out = 0u

        val windowBits =
            when (algorithm) {
                CompressionAlgorithm.Deflate -> WINDOW_BITS_ZLIB
                CompressionAlgorithm.Raw -> WINDOW_BITS_RAW
                CompressionAlgorithm.Gzip -> WINDOW_BITS_GZIP
            }

        val result =
            deflateInit2(
                s.ptr,
                level.value,
                Z_DEFLATED,
                windowBits,
                8,
                0,
            )

        if (result != Z_OK) {
            nativeHeap.free(s.rawPtr)
            throw CompressionException("deflateInit2 failed with code: $result")
        }

        streamPtr = s.ptr
    }

    override fun compress(
        input: ReadBuffer,
        onOutput: (ReadBuffer) -> Unit,
    ) {
        check(!closed) { "Compressor is closed" }
        val s = streamPtr ?: throw CompressionException("Stream not initialized")

        val remaining = input.remaining()
        if (remaining == 0) return

        val inputPosition = input.position()

        withInputPointer(input) { inputPtr ->
            s.pointed.next_in = (inputPtr + inputPosition)?.reinterpret()
            s.pointed.avail_in = remaining.convert()

            while (s.pointed.avail_in > 0u) {
                val chunk = allocator.allocate(outputBufferSize)
                withOutputPointer(chunk) { chunkPtr ->
                    s.pointed.next_out = chunkPtr.reinterpret()
                    s.pointed.avail_out = outputBufferSize.convert()

                    val result = deflate(s, Z_NO_FLUSH)
                    if (result != Z_OK && result != Z_STREAM_END) {
                        throw CompressionException("deflate failed with code: $result")
                    }

                    val produced = outputBufferSize - s.pointed.avail_out.toInt()
                    if (produced > 0) {
                        chunk.position(produced)
                        chunk.resetForRead()
                        onOutput(chunk)
                    }
                }
            }
        }

        input.position(inputPosition + remaining)
    }

    override fun flush(onOutput: (ReadBuffer) -> Unit) {
        check(!closed) { "Compressor is closed" }
        val s = streamPtr ?: throw CompressionException("Stream not initialized")

        s.pointed.next_in = null
        s.pointed.avail_in = 0u

        // Drain with Z_SYNC_FLUSH until no more output
        while (true) {
            val chunk = allocator.allocate(outputBufferSize)
            val shouldBreak =
                withOutputPointer(chunk) { chunkPtr ->
                    s.pointed.next_out = chunkPtr.reinterpret()
                    s.pointed.avail_out = outputBufferSize.convert()

                    val result = deflate(s, Z_SYNC_FLUSH)
                    if (result != Z_OK && result != Z_STREAM_END) {
                        throw CompressionException("deflate flush failed with code: $result")
                    }

                    val produced = outputBufferSize - s.pointed.avail_out.toInt()
                    if (produced > 0) {
                        chunk.position(produced)
                        chunk.resetForRead()
                        onOutput(chunk)
                    }

                    // If output buffer wasn't filled, flush is complete
                    s.pointed.avail_out > 0u
                }
            if (shouldBreak) break
        }
    }

    override fun finish(onOutput: (ReadBuffer) -> Unit) {
        check(!closed) { "Compressor is closed" }
        val s = streamPtr ?: throw CompressionException("Stream not initialized")

        s.pointed.next_in = null
        s.pointed.avail_in = 0u

        var finished = false
        while (!finished) {
            val chunk = allocator.allocate(outputBufferSize)
            withOutputPointer(chunk) { chunkPtr ->
                s.pointed.next_out = chunkPtr.reinterpret()
                s.pointed.avail_out = outputBufferSize.convert()

                val result = deflate(s, Z_FINISH)
                when (result) {
                    Z_STREAM_END -> finished = true
                    Z_OK -> {}
                    else -> throw CompressionException("deflate finish failed with code: $result")
                }

                val produced = outputBufferSize - s.pointed.avail_out.toInt()
                if (produced > 0) {
                    chunk.position(produced)
                    chunk.resetForRead()
                    onOutput(chunk)
                }
            }
        }
    }

    override fun reset() {
        streamPtr?.let {
            deflateEnd(it)
            nativeHeap.free(it.pointed.rawPtr)
        }
        streamPtr = null
        initStream()
    }

    override fun close() {
        if (!closed) {
            streamPtr?.let {
                deflateEnd(it)
                nativeHeap.free(it.pointed.rawPtr)
            }
            streamPtr = null
            closed = true
        }
    }
}

/**
 * Apple streaming decompressor using zlib z_stream for true incremental decompression.
 * Zero-copy: writes directly to output buffers.
 */
@OptIn(ExperimentalForeignApi::class)
private class AppleZlibStreamingDecompressor(
    private val algorithm: CompressionAlgorithm,
    override val allocator: BufferAllocator,
    private val outputBufferSize: Int,
) : StreamingDecompressor {
    private var streamPtr: CPointer<z_stream>? = null
    private var closed = false
    private var streamEnded = false

    init {
        initStream()
    }

    private fun initStream() {
        val s = nativeHeap.alloc<z_stream>()
        s.zalloc = null
        s.zfree = null
        s.opaque = null
        s.next_in = null
        s.avail_in = 0u
        s.next_out = null
        s.avail_out = 0u

        val windowBits =
            when (algorithm) {
                CompressionAlgorithm.Deflate -> WINDOW_BITS_ZLIB
                CompressionAlgorithm.Raw -> WINDOW_BITS_RAW
                CompressionAlgorithm.Gzip -> WINDOW_BITS_GZIP
            }

        val result = inflateInit2(s.ptr, windowBits)

        if (result != Z_OK) {
            nativeHeap.free(s.rawPtr)
            throw CompressionException("inflateInit2 failed with code: $result")
        }

        streamPtr = s.ptr
        streamEnded = false
    }

    override fun decompress(
        input: ReadBuffer,
        onOutput: (ReadBuffer) -> Unit,
    ) {
        check(!closed) { "Decompressor is closed" }
        if (streamEnded) return

        val s = streamPtr ?: throw CompressionException("Stream not initialized")

        val remaining = input.remaining()
        if (remaining == 0) return

        val inputPosition = input.position()

        val consumed =
            withInputPointer(input) { inputPtr ->
                s.pointed.next_in = (inputPtr + inputPosition)?.reinterpret()
                s.pointed.avail_in = remaining.convert()

                while (s.pointed.avail_in > 0u && !streamEnded) {
                    val chunk = allocator.allocate(outputBufferSize)
                    withOutputPointer(chunk) { chunkPtr ->
                        s.pointed.next_out = chunkPtr.reinterpret()
                        s.pointed.avail_out = outputBufferSize.convert()

                        val result = inflate(s, Z_SYNC_FLUSH)
                        when (result) {
                            Z_OK -> {}
                            Z_STREAM_END -> streamEnded = true
                            else -> throw CompressionException("inflate failed with code: $result")
                        }

                        val produced = outputBufferSize - s.pointed.avail_out.toInt()
                        if (produced > 0) {
                            chunk.position(produced)
                            chunk.resetForRead()
                            onOutput(chunk)
                        }
                    }
                }

                remaining - s.pointed.avail_in.toInt()
            }

        input.position(inputPosition + consumed)
    }

    override fun flush(onOutput: (ReadBuffer) -> Unit) {
        // No-op: Apple decompressor emits output eagerly in decompress(),
        // no partial buffering that needs flushing.
    }

    override fun finish(onOutput: (ReadBuffer) -> Unit) {
        check(!closed) { "Decompressor is closed" }
        if (streamEnded) return

        val s = streamPtr ?: throw CompressionException("Stream not initialized")

        s.pointed.next_in = null
        s.pointed.avail_in = 0u

        while (!streamEnded) {
            val chunk = allocator.allocate(outputBufferSize)
            val shouldBreak =
                withOutputPointer(chunk) { chunkPtr ->
                    s.pointed.next_out = chunkPtr.reinterpret()
                    s.pointed.avail_out = outputBufferSize.convert()

                    val result = inflate(s, Z_FINISH)
                    when (result) {
                        Z_OK -> {}
                        Z_STREAM_END -> streamEnded = true
                        -5 -> {
                            // Z_BUF_ERROR (-5) with no input means the stream produced all
                            // available output. This is expected for raw deflate streams
                            // without BFINAL=1 (e.g., WebSocket per-message-deflate RFC 7692).
                            streamEnded = true
                        }
                        else -> throw CompressionException("inflate finish failed with code: $result")
                    }

                    val produced = outputBufferSize - s.pointed.avail_out.toInt()
                    if (produced > 0) {
                        chunk.position(produced)
                        chunk.resetForRead()
                        onOutput(chunk)
                    }

                    s.pointed.avail_out > 0u
                }
            if (shouldBreak) break
        }
    }

    override fun reset() {
        streamPtr?.let {
            inflateEnd(it)
            nativeHeap.free(it.pointed.rawPtr)
        }
        streamPtr = null
        initStream()
    }

    override fun close() {
        if (!closed) {
            streamPtr?.let {
                inflateEnd(it)
                nativeHeap.free(it.pointed.rawPtr)
            }
            streamPtr = null
            closed = true
        }
    }
}

/**
 * Execute a block with a pointer to the buffer's data (read path).
 * Handles PooledBuffer unwrapping and ByteArrayBuffer pinning.
 */
@OptIn(ExperimentalForeignApi::class)
private inline fun <R> withInputPointer(
    buffer: ReadBuffer,
    block: (CPointer<ByteVar>) -> R,
): R =
    when (buffer) {
        is MutableDataBufferSlice -> block(buffer.bytePointer)
        is MutableDataBuffer -> {
            @Suppress("UNCHECKED_CAST")
            block(buffer.data.mutableBytes as CPointer<ByteVar>)
        }
        is ByteArrayBuffer -> {
            buffer.backingArray.usePinned { pinned ->
                block(pinned.addressOf(0))
            }
        }
        else -> {
            // Unwrap PooledBuffer or other wrappers
            val unwrapped = (buffer as? com.ditchoom.buffer.PlatformBuffer)?.unwrap()
            if (unwrapped != null && unwrapped !== buffer) {
                when (unwrapped) {
                    is MutableDataBufferSlice -> block(unwrapped.bytePointer)
                    is MutableDataBuffer -> {
                        @Suppress("UNCHECKED_CAST")
                        block(unwrapped.data.mutableBytes as CPointer<ByteVar>)
                    }
                    is ByteArrayBuffer -> {
                        unwrapped.backingArray.usePinned { pinned ->
                            block(pinned.addressOf(0))
                        }
                    }
                    else -> throw CompressionException("Unsupported buffer type: ${unwrapped::class}")
                }
            } else {
                throw CompressionException("Unsupported buffer type: ${buffer::class}")
            }
        }
    }

/**
 * Execute a block with a pointer to the buffer's data (write/output path).
 * Handles PooledBuffer unwrapping and ByteArrayBuffer pinning.
 */
@OptIn(ExperimentalForeignApi::class)
private inline fun <R> withOutputPointer(
    buffer: ReadWriteBuffer,
    block: (CPointer<ByteVar>) -> R,
): R {
    // Unwrap PooledBuffer or other wrappers to get the actual buffer
    val actual = (buffer as? PlatformBuffer)?.unwrap() ?: buffer
    return when (actual) {
        is MutableDataBufferSlice -> block(actual.bytePointer)
        is MutableDataBuffer -> {
            @Suppress("UNCHECKED_CAST")
            block(actual.data.mutableBytes as CPointer<ByteVar>)
        }
        is ByteArrayBuffer -> {
            actual.backingArray.usePinned { pinned ->
                block(pinned.addressOf(0))
            }
        }
        else -> throw CompressionException("Unsupported output buffer type: ${actual::class}")
    }
}

// =============================================================================
// Suspending Variants (wrap sync implementations)
// =============================================================================

actual fun SuspendingStreamingCompressor.Companion.create(
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    allocator: BufferAllocator,
): SuspendingStreamingCompressor =
    SyncWrappingSuspendingCompressor(
        StreamingCompressor.create(algorithm, level, allocator),
    )

actual fun SuspendingStreamingDecompressor.Companion.create(
    algorithm: CompressionAlgorithm,
    allocator: BufferAllocator,
): SuspendingStreamingDecompressor =
    SyncWrappingSuspendingDecompressor(
        StreamingDecompressor.create(algorithm, allocator),
    )
