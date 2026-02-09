package com.ditchoom.buffer.compression

import com.ditchoom.buffer.ByteArrayBuffer
import com.ditchoom.buffer.NativeMemoryAccess
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadWriteBuffer
import com.ditchoom.buffer.managedMemoryAccess
import com.ditchoom.buffer.nativeMemoryAccess
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
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.Z_SYNC_FLUSH
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2
import platform.zlib.deflateReset
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.inflateReset
import platform.zlib.z_stream

/**
 * Linux streaming compressor factory using z_stream for true incremental compression.
 */
actual fun StreamingCompressor.Companion.create(
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    allocator: BufferAllocator,
    outputBufferSize: Int,
): StreamingCompressor = LinuxZlibStreamingCompressor(algorithm, level, allocator, outputBufferSize)

/**
 * Linux streaming decompressor factory using z_stream for true incremental decompression.
 */
actual fun StreamingDecompressor.Companion.create(
    algorithm: CompressionAlgorithm,
    allocator: BufferAllocator,
    outputBufferSize: Int,
    expectedSize: Int,
): StreamingDecompressor = LinuxZlibStreamingDecompressor(algorithm, allocator, outputBufferSize)

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
 * Allocates a buffer with native memory access from the allocator with runtime validation.
 * Linux streaming compression requires direct memory access via NativeMemoryAccess.
 * Returns both the ReadWriteBuffer (for position/limit operations) and its native address.
 * Supports PlatformBuffer with NativeMemoryAccess.
 */
private fun BufferAllocator.allocateNativeBuffer(size: Int): Pair<ReadWriteBuffer, Long> {
    val buffer = allocate(size)
    val nativeAccess =
        (buffer as ReadBuffer).nativeMemoryAccess
            ?: throw CompressionException(
                "Linux streaming compression requires NativeMemoryAccess allocator, got ${buffer::class.simpleName}",
            )
    return buffer to nativeAccess.nativeAddress
}

/**
 * Frees a buffer's native memory if it is a PlatformBuffer.
 */
private fun ReadWriteBuffer.releaseOrFree() {
    (this as? PlatformBuffer)?.freeNativeMemory()
}

/**
 * Linux streaming compressor using zlib z_stream for true incremental compression.
 * Reuses output buffers across iterations to avoid per-iteration malloc/free (matches JVM pattern).
 */
@OptIn(ExperimentalForeignApi::class)
private class LinuxZlibStreamingCompressor(
    private val algorithm: CompressionAlgorithm,
    private val level: CompressionLevel,
    override val allocator: BufferAllocator,
    private val outputBufferSize: Int,
) : StreamingCompressor {
    private var streamPtr: CPointer<z_stream>? = null
    private var closed = false

    // Reusable output buffer — persists across iterations and calls.
    // Only allocated when null, emitted when full, freed on reset/close.
    private var currentOutput: ReadWriteBuffer? = null
    private var currentOutputAddr: Long = 0L
    private var currentOutputWritten: Int = 0

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

    private fun ensureOutput() {
        if (currentOutput == null) {
            val (buf, addr) = allocator.allocateNativeBuffer(outputBufferSize)
            currentOutput = buf
            currentOutputAddr = addr
            currentOutputWritten = 0
        }
    }

    private fun emitFullOutput(onOutput: (ReadBuffer) -> Unit) {
        val output = currentOutput!!
        output.position(currentOutputWritten)
        output.resetForRead()
        onOutput(output)
        currentOutput = null
    }

    private fun emitPartialOutput(onOutput: (ReadBuffer) -> Unit) {
        val output = currentOutput ?: return
        if (currentOutputWritten > 0) {
            output.position(currentOutputWritten)
            output.resetForRead()
            onOutput(output)
        } else {
            output.releaseOrFree()
        }
        currentOutput = null
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
                ensureOutput()
                val available = outputBufferSize - currentOutputWritten
                s.pointed.next_out = (currentOutputAddr + currentOutputWritten).toCPointer<ByteVar>()!!.reinterpret()
                s.pointed.avail_out = available.convert()

                val result = deflate(s, Z_NO_FLUSH)
                if (result != Z_OK && result != Z_STREAM_END) {
                    throw CompressionException("deflate failed with code: $result")
                }

                val produced = available - s.pointed.avail_out.toInt()
                currentOutputWritten += produced

                when {
                    currentOutputWritten >= outputBufferSize -> emitFullOutput(onOutput)
                    produced == 0 -> {
                        // zlib couldn't produce output — if the buffer has data, emit
                        // partial and retry with a fresh buffer (remaining space may be
                        // too small for zlib to make progress).
                        if (currentOutputWritten > 0) {
                            emitPartialOutput(onOutput)
                        } else {
                            break
                        }
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

        while (true) {
            ensureOutput()
            val available = outputBufferSize - currentOutputWritten
            s.pointed.next_out = (currentOutputAddr + currentOutputWritten).toCPointer<ByteVar>()!!.reinterpret()
            s.pointed.avail_out = available.convert()

            val result = deflate(s, Z_SYNC_FLUSH)
            if (result != Z_OK && result != Z_STREAM_END) {
                throw CompressionException("deflate flush failed with code: $result")
            }

            val produced = available - s.pointed.avail_out.toInt()
            currentOutputWritten += produced

            if (currentOutputWritten >= outputBufferSize) {
                emitFullOutput(onOutput)
            } else {
                // Output buffer not full — flush is complete
                break
            }
        }

        emitPartialOutput(onOutput)
    }

    override fun finish(onOutput: (ReadBuffer) -> Unit) {
        check(!closed) { "Compressor is closed" }
        val s = streamPtr ?: throw CompressionException("Stream not initialized")

        s.pointed.next_in = null
        s.pointed.avail_in = 0u

        var finished = false
        while (!finished) {
            ensureOutput()
            val available = outputBufferSize - currentOutputWritten
            s.pointed.next_out = (currentOutputAddr + currentOutputWritten).toCPointer<ByteVar>()!!.reinterpret()
            s.pointed.avail_out = available.convert()

            val result = deflate(s, Z_FINISH)
            when (result) {
                Z_STREAM_END -> finished = true
                Z_OK -> {}
                else -> throw CompressionException("deflate finish failed with code: $result")
            }

            val produced = available - s.pointed.avail_out.toInt()
            currentOutputWritten += produced

            when {
                currentOutputWritten >= outputBufferSize -> emitFullOutput(onOutput)
                produced == 0 && !finished -> break
            }
        }

        emitPartialOutput(onOutput)
    }

    override fun reset() {
        currentOutput?.releaseOrFree()
        currentOutput = null
        currentOutputWritten = 0
        val s = streamPtr ?: return
        val result = deflateReset(s)
        if (result != Z_OK) {
            deflateEnd(s)
            nativeHeap.free(s.pointed.rawPtr)
            streamPtr = null
            initStream()
        }
    }

    override fun close() {
        if (!closed) {
            currentOutput?.releaseOrFree()
            currentOutput = null
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
 * Linux streaming decompressor using zlib z_stream for true incremental decompression.
 * Reuses output buffers across iterations to avoid per-iteration malloc/free (matches JVM pattern).
 */
@OptIn(ExperimentalForeignApi::class)
private class LinuxZlibStreamingDecompressor(
    private val algorithm: CompressionAlgorithm,
    override val allocator: BufferAllocator,
    private val outputBufferSize: Int,
) : StreamingDecompressor {
    private var streamPtr: CPointer<z_stream>? = null
    private var closed = false
    private var streamEnded = false

    // Reusable output buffer — persists across iterations and calls.
    private var currentOutput: ReadWriteBuffer? = null
    private var currentOutputAddr: Long = 0L
    private var currentOutputWritten: Int = 0

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

    private fun ensureOutput() {
        if (currentOutput == null) {
            val (buf, addr) = allocator.allocateNativeBuffer(outputBufferSize)
            currentOutput = buf
            currentOutputAddr = addr
            currentOutputWritten = 0
        }
    }

    private fun emitFullOutput(onOutput: (ReadBuffer) -> Unit) {
        val output = currentOutput!!
        output.position(currentOutputWritten)
        output.resetForRead()
        onOutput(output)
        currentOutput = null
    }

    private fun emitPartialOutput(onOutput: (ReadBuffer) -> Unit) {
        val output = currentOutput ?: return
        if (currentOutputWritten > 0) {
            output.position(currentOutputWritten)
            output.resetForRead()
            onOutput(output)
        } else {
            output.releaseOrFree()
        }
        currentOutput = null
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
                    ensureOutput()
                    val available = outputBufferSize - currentOutputWritten
                    s.pointed.next_out = (currentOutputAddr + currentOutputWritten).toCPointer<ByteVar>()!!.reinterpret()
                    s.pointed.avail_out = available.convert()

                    val result = inflate(s, Z_SYNC_FLUSH)
                    when (result) {
                        Z_OK -> {}
                        Z_STREAM_END -> streamEnded = true
                        else -> throw CompressionException("inflate failed with code: $result")
                    }

                    val produced = available - s.pointed.avail_out.toInt()
                    currentOutputWritten += produced

                    when {
                        currentOutputWritten >= outputBufferSize -> emitFullOutput(onOutput)
                        produced == 0 -> {
                            if (currentOutputWritten > 0) {
                                emitPartialOutput(onOutput)
                            } else {
                                break
                            }
                        }
                    }
                }

                remaining - s.pointed.avail_in.toInt()
            }

        input.position(inputPosition + consumed)
    }

    override fun finish(onOutput: (ReadBuffer) -> Unit) {
        check(!closed) { "Decompressor is closed" }
        if (streamEnded) {
            emitPartialOutput(onOutput)
            return
        }

        val s = streamPtr ?: throw CompressionException("Stream not initialized")

        s.pointed.next_in = null
        s.pointed.avail_in = 0u

        while (!streamEnded) {
            ensureOutput()
            val available = outputBufferSize - currentOutputWritten
            s.pointed.next_out = (currentOutputAddr + currentOutputWritten).toCPointer<ByteVar>()!!.reinterpret()
            s.pointed.avail_out = available.convert()

            val result = inflate(s, Z_FINISH)
            when (result) {
                Z_OK -> {}
                Z_STREAM_END -> streamEnded = true
                Z_BUF_ERROR -> {
                    // Z_BUF_ERROR with no input means the stream produced all
                    // available output. This is expected for raw deflate streams
                    // without BFINAL=1 (e.g., WebSocket per-message-deflate RFC 7692).
                    streamEnded = true
                }
                else -> throw CompressionException("inflate finish failed with code: $result")
            }

            val produced = available - s.pointed.avail_out.toInt()
            currentOutputWritten += produced

            if (currentOutputWritten >= outputBufferSize) {
                emitFullOutput(onOutput)
            } else if (s.pointed.avail_out > 0u) {
                break
            }
        }

        emitPartialOutput(onOutput)
    }

    override fun reset() {
        currentOutput?.releaseOrFree()
        currentOutput = null
        currentOutputWritten = 0
        val s = streamPtr ?: return
        val result = inflateReset(s)
        if (result != Z_OK) {
            inflateEnd(s)
            nativeHeap.free(s.pointed.rawPtr)
            streamPtr = null
            initStream()
        } else {
            streamEnded = false
        }
    }

    override fun close() {
        if (!closed) {
            currentOutput?.releaseOrFree()
            currentOutput = null
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
 * Execute a block with a pointer to the buffer's data.
 * Handles pinning for ByteArrayBuffer to ensure the pointer remains valid.
 */
@OptIn(ExperimentalForeignApi::class)
private inline fun <R> withInputPointer(
    buffer: ReadBuffer,
    block: (CPointer<ByteVar>) -> R,
): R =
    when {
        buffer is NativeMemoryAccess -> block(buffer.nativeAddress.toCPointer<ByteVar>()!!)
        buffer.nativeMemoryAccess != null -> block(buffer.nativeMemoryAccess!!.nativeAddress.toCPointer<ByteVar>()!!)
        buffer is ByteArrayBuffer -> {
            val array = buffer.backingArray
            if (array.isEmpty()) {
                throw CompressionException("Cannot get pointer to empty buffer")
            }
            array.usePinned { pinned ->
                block(pinned.addressOf(0))
            }
        }
        buffer.managedMemoryAccess != null -> {
            val array = buffer.managedMemoryAccess!!.backingArray
            if (array.isEmpty()) {
                throw CompressionException("Cannot get pointer to empty buffer")
            }
            array.usePinned { pinned ->
                block(pinned.addressOf(0))
            }
        }
        else -> throw CompressionException("Unsupported buffer type: ${buffer::class}")
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
