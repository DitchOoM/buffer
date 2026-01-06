package com.ditchoom.buffer.compression

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadWriteBuffer
import java.nio.Buffer
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

// =============================================================================
// Factory Functions
// =============================================================================

actual fun StreamingCompressor.Companion.create(
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    allocator: BufferAllocator,
    outputBufferSize: Int,
): StreamingCompressor =
    when (algorithm) {
        CompressionAlgorithm.Gzip -> JvmGzipStreamingCompressor(level, allocator, outputBufferSize)
        CompressionAlgorithm.Deflate -> JvmDeflateStreamingCompressor(level, nowrap = false, allocator, outputBufferSize)
        CompressionAlgorithm.Raw -> JvmDeflateStreamingCompressor(level, nowrap = true, allocator, outputBufferSize)
    }

actual fun StreamingDecompressor.Companion.create(
    algorithm: CompressionAlgorithm,
    allocator: BufferAllocator,
    outputBufferSize: Int,
    expectedSize: Int,
): StreamingDecompressor =
    when (algorithm) {
        CompressionAlgorithm.Gzip -> JvmGzipStreamingDecompressor(allocator, outputBufferSize, expectedSize)
        CompressionAlgorithm.Deflate -> JvmInflateStreamingDecompressor(nowrap = false, allocator, outputBufferSize, expectedSize)
        CompressionAlgorithm.Raw -> JvmInflateStreamingDecompressor(nowrap = true, allocator, outputBufferSize, expectedSize)
    }

// =============================================================================
// Shared Helpers - Use try-catch for ByteBuffer methods (Java 11+ / Android API 35+)
// =============================================================================

/**
 * Sets deflater input from a ReadBuffer, using ByteBuffer when available.
 */
private fun Deflater.setInputFrom(buffer: ReadBuffer) {
    val remaining = buffer.remaining()
    val byteBuffer = (buffer as? BaseJvmBuffer)?.byteBuffer

    if (byteBuffer != null) {
        try {
            setInput(byteBuffer)
            return
        } catch (_: NoSuchMethodError) {
            // ByteBuffer overload not available, use fallback
        }

        // Fallback: use array if available
        if (byteBuffer.hasArray()) {
            setInput(byteBuffer.array(), byteBuffer.arrayOffset() + byteBuffer.position(), remaining)
            (byteBuffer as Buffer).position(byteBuffer.position() + remaining)
            return
        }

        // Fallback: copy to byte array
        val data = ByteArray(remaining)
        byteBuffer.get(data)
        setInput(data)
    } else {
        setInput(buffer.readByteArray(remaining))
    }
}

/**
 * Deflates into a ByteBuffer, using the most efficient method available.
 */
private fun Deflater.deflateInto(buffer: ByteBuffer): Int {
    try {
        return deflate(buffer)
    } catch (_: NoSuchMethodError) {
        // ByteBuffer overload not available, use fallback
    }

    // Fallback: use array if available
    if (buffer.hasArray()) {
        val count = deflate(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining())
        (buffer as Buffer).position(buffer.position() + count)
        return count
    }

    // Fallback: use temp array
    val temp = ByteArray(buffer.remaining())
    val count = deflate(temp)
    buffer.put(temp, 0, count)
    return count
}

/**
 * Sets inflater input from a ReadBuffer, using ByteBuffer when available.
 */
private fun Inflater.setInputFrom(buffer: ReadBuffer) {
    val remaining = buffer.remaining()
    if (remaining == 0) return

    val byteBuffer = (buffer as? BaseJvmBuffer)?.byteBuffer

    if (byteBuffer != null) {
        try {
            setInput(byteBuffer)
            return
        } catch (_: NoSuchMethodError) {
            // ByteBuffer overload not available, use fallback
        }

        // Fallback: use array if available
        if (byteBuffer.hasArray()) {
            setInput(byteBuffer.array(), byteBuffer.arrayOffset() + byteBuffer.position(), remaining)
            (byteBuffer as Buffer).position(byteBuffer.position() + remaining)
            return
        }

        // Fallback: copy to byte array
        val data = ByteArray(remaining)
        byteBuffer.get(data)
        setInput(data)
    } else {
        setInput(buffer.readByteArray(remaining))
    }
}

/**
 * Inflates into a ByteBuffer, using the most efficient method available.
 */
private fun Inflater.inflateInto(buffer: ByteBuffer): Int {
    try {
        return inflate(buffer)
    } catch (_: NoSuchMethodError) {
        // ByteBuffer overload not available, use fallback
    }

    // Fallback: use array if available
    if (buffer.hasArray()) {
        val count = inflate(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining())
        (buffer as Buffer).position(buffer.position() + count)
        return count
    }

    // Fallback: use temp array
    val temp = ByteArray(buffer.remaining())
    val count = inflate(temp)
    buffer.put(temp, 0, count)
    return count
}

/**
 * Updates CRC from a ReadBuffer without consuming the buffer.
 * Returns the number of bytes processed.
 */
private fun CRC32.updateFrom(buffer: ReadBuffer): Int {
    val remaining = buffer.remaining()
    val byteBuffer = (buffer as? BaseJvmBuffer)?.byteBuffer
    val savedPosition = buffer.position()

    if (byteBuffer != null) {
        try {
            @Suppress("Since15")
            update(byteBuffer)
            buffer.position(savedPosition)
            return remaining
        } catch (_: NoSuchMethodError) {
            // ByteBuffer overload not available (Java 8 / Android < API 26)
        }

        // Fallback: use array if available
        if (byteBuffer.hasArray()) {
            update(byteBuffer.array(), byteBuffer.arrayOffset() + byteBuffer.position(), remaining)
            buffer.position(savedPosition)
            return remaining
        }
    }

    // Fallback: read to byte array
    update(buffer.readByteArray(remaining))
    buffer.position(savedPosition)
    return remaining
}

/**
 * Emits a partial buffer if it has data.
 */
private inline fun emitPartialBuffer(
    output: ReadWriteBuffer?,
    onOutput: (ReadBuffer) -> Unit,
): Boolean {
    if (output == null) return false
    val buffer = (output as BaseJvmBuffer).byteBuffer
    if (buffer.position() > 0) {
        output.resetForRead()
        onOutput(output)
        return true
    }
    return false
}

// =============================================================================
// Compressors
// =============================================================================

private class JvmDeflateStreamingCompressor(
    level: CompressionLevel,
    nowrap: Boolean,
    override val allocator: BufferAllocator,
    private val outputBufferSize: Int,
) : StreamingCompressor {
    private val deflater = Deflater(level.value, nowrap)
    private var currentOutput: ReadWriteBuffer? = null
    private var closed = false

    override fun compress(
        input: ReadBuffer,
        onOutput: (ReadBuffer) -> Unit,
    ) {
        check(!closed) { "Compressor is closed" }
        deflater.setInputFrom(input)
        drainDeflater(onOutput, finishing = false)
    }

    override fun finish(onOutput: (ReadBuffer) -> Unit) {
        check(!closed) { "Compressor is closed" }
        deflater.finish()
        drainDeflater(onOutput, finishing = true)
        emitPartialBuffer(currentOutput, onOutput)
        currentOutput = null
    }

    override fun reset() {
        deflater.reset()
        currentOutput = null
    }

    override fun close() {
        if (!closed) {
            deflater.end()
            currentOutput = null
            closed = true
        }
    }

    private fun drainDeflater(
        onOutput: (ReadBuffer) -> Unit,
        finishing: Boolean,
    ) {
        val condition =
            if (finishing) {
                { !deflater.finished() }
            } else {
                { !deflater.needsInput() }
            }

        while (condition()) {
            if (currentOutput == null) {
                currentOutput = allocator.allocate(outputBufferSize)
            }

            val output = currentOutput!!
            val buffer = (output as BaseJvmBuffer).byteBuffer
            val count = deflater.deflateInto(buffer)

            when {
                count > 0 && buffer.remaining() == 0 -> {
                    output.resetForRead()
                    onOutput(output)
                    currentOutput = null
                }
                count == 0 -> {
                    if (!finishing || deflater.needsInput()) break
                }
            }
        }
    }
}

private class JvmGzipStreamingCompressor(
    level: CompressionLevel,
    override val allocator: BufferAllocator,
    private val outputBufferSize: Int,
) : StreamingCompressor {
    private val deflater = Deflater(level.value, true)
    private val crc = CRC32()
    private var currentOutput: ReadWriteBuffer? = null
    private var totalInputBytes = 0L
    private var headerWritten = false
    private var closed = false

    override fun compress(
        input: ReadBuffer,
        onOutput: (ReadBuffer) -> Unit,
    ) {
        check(!closed) { "Compressor is closed" }

        if (!headerWritten) {
            onOutput(allocator.allocateGzipHeader())
            headerWritten = true
        }

        totalInputBytes += crc.updateFrom(input)
        deflater.setInputFrom(input)
        drainDeflater(onOutput, finishing = false)
    }

    override fun finish(onOutput: (ReadBuffer) -> Unit) {
        check(!closed) { "Compressor is closed" }

        if (!headerWritten) {
            onOutput(allocator.allocateGzipHeader())
        }

        deflater.finish()
        drainDeflater(onOutput, finishing = true)
        emitPartialBuffer(currentOutput, onOutput)

        // Write trailer (8 bytes)
        val trailerBuffer = allocator.allocate(8)
        trailerBuffer.writeLong(gzipTrailerLong(crc.value.toInt(), (totalInputBytes and 0xFFFFFFFFL).toInt()))
        trailerBuffer.resetForRead()
        onOutput(trailerBuffer)
        currentOutput = null
    }

    override fun reset() {
        deflater.reset()
        crc.reset()
        totalInputBytes = 0L
        headerWritten = false
        currentOutput = null
    }

    override fun close() {
        if (!closed) {
            deflater.end()
            currentOutput = null
            closed = true
        }
    }

    private fun drainDeflater(
        onOutput: (ReadBuffer) -> Unit,
        finishing: Boolean,
    ) {
        val condition =
            if (finishing) {
                { !deflater.finished() }
            } else {
                { !deflater.needsInput() }
            }

        while (condition()) {
            if (currentOutput == null) {
                currentOutput = allocator.allocate(outputBufferSize)
            }

            val output = currentOutput!!
            val buffer = (output as BaseJvmBuffer).byteBuffer
            val count = deflater.deflateInto(buffer)

            when {
                count > 0 && buffer.remaining() == 0 -> {
                    output.resetForRead()
                    onOutput(output)
                    currentOutput = null
                }
                count == 0 -> {
                    if (!finishing || deflater.needsInput()) break
                }
            }
        }
    }
}

// =============================================================================
// Decompressors
// =============================================================================

private class JvmInflateStreamingDecompressor(
    nowrap: Boolean,
    override val allocator: BufferAllocator,
    outputBufferSize: Int,
    expectedSize: Int,
) : StreamingDecompressor {
    private val inflater = Inflater(nowrap)
    private val effectiveBufferSize = if (expectedSize > 0) minOf(expectedSize, outputBufferSize) else outputBufferSize
    private var currentOutput: ReadWriteBuffer? = null
    private var closed = false

    override fun decompress(
        input: ReadBuffer,
        onOutput: (ReadBuffer) -> Unit,
    ) {
        check(!closed) { "Decompressor is closed" }
        inflater.setInputFrom(input)
        drainInflater(onOutput)
    }

    override fun finish(onOutput: (ReadBuffer) -> Unit) {
        check(!closed) { "Decompressor is closed" }
        drainInflater(onOutput)
        emitPartialBuffer(currentOutput, onOutput)
        currentOutput = null

        if (!inflater.finished() && !inflater.needsInput()) {
            throw CompressionException("Incomplete compressed data stream")
        }
    }

    override fun reset() {
        inflater.reset()
        currentOutput = null
    }

    override fun close() {
        if (!closed) {
            inflater.end()
            currentOutput = null
            closed = true
        }
    }

    private fun drainInflater(onOutput: (ReadBuffer) -> Unit) {
        while (!inflater.needsInput() && !inflater.finished()) {
            if (currentOutput == null) {
                currentOutput = allocator.allocate(effectiveBufferSize)
            }

            val output = currentOutput!!
            val buffer = (output as BaseJvmBuffer).byteBuffer
            val count = inflater.inflateInto(buffer)

            when {
                count > 0 && buffer.remaining() == 0 -> {
                    output.resetForRead()
                    onOutput(output)
                    currentOutput = null
                }
                count == 0 -> {
                    if (inflater.needsDictionary()) {
                        throw CompressionException("Dictionary required")
                    }
                    break
                }
            }
        }
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

private class JvmGzipStreamingDecompressor(
    override val allocator: BufferAllocator,
    outputBufferSize: Int,
    expectedSize: Int,
) : StreamingDecompressor {
    private val inflater = Inflater(true)
    private val effectiveBufferSize = if (expectedSize > 0) minOf(expectedSize, outputBufferSize) else outputBufferSize
    private var currentOutput: ReadWriteBuffer? = null
    private var headerParsed = false
    private val headerBytes = ByteArray(12)
    private var headerPos = 0
    private var headerFlags = -1 // -1 = fixed header not yet parsed
    private var closed = false

    override fun decompress(
        input: ReadBuffer,
        onOutput: (ReadBuffer) -> Unit,
    ) {
        check(!closed) { "Decompressor is closed" }

        if (!headerParsed) {
            if (!parseGzipHeader(input)) return
            headerParsed = true
        }

        inflater.setInputFrom(input)
        drainInflater(onOutput)
    }

    override fun finish(onOutput: (ReadBuffer) -> Unit) {
        check(!closed) { "Decompressor is closed" }
        drainInflater(onOutput)
        emitPartialBuffer(currentOutput, onOutput)
        currentOutput = null
    }

    override fun reset() {
        inflater.reset()
        headerParsed = false
        headerPos = 0
        headerFlags = -1
        currentOutput = null
    }

    override fun close() {
        if (!closed) {
            inflater.end()
            currentOutput = null
            closed = true
        }
    }

    private fun drainInflater(onOutput: (ReadBuffer) -> Unit) {
        while (!inflater.needsInput() && !inflater.finished()) {
            if (currentOutput == null) {
                currentOutput = allocator.allocate(effectiveBufferSize)
            }

            val output = currentOutput!!
            val buffer = (output as BaseJvmBuffer).byteBuffer
            val count = inflater.inflateInto(buffer)

            when {
                count > 0 && buffer.remaining() == 0 -> {
                    output.resetForRead()
                    onOutput(output)
                    currentOutput = null
                }
                count == 0 -> {
                    if (inflater.needsDictionary()) {
                        throw CompressionException("Dictionary required")
                    }
                    break
                }
            }
        }
    }

    private fun parseGzipHeader(buffer: ReadBuffer): Boolean {
        // Parse fixed 10-byte header if not done yet
        if (headerFlags < 0) {
            // Fast path: read all 10 bytes at once if available
            if (headerPos == 0 && buffer.remaining() >= 10) {
                val first8 = buffer.readLong()
                buffer.readShort() // xfl + os (ignored)

                val magic = (first8 ushr 48).toInt()
                if (magic != GzipFormat.MAGIC) {
                    throw CompressionException("Invalid gzip magic number")
                }

                headerFlags = ((first8 ushr 32) and 0xFF).toInt()
                headerPos = 10
            } else {
                // Slow path: accumulate bytes for partial headers
                while (buffer.remaining() > 0 && headerPos < 10) {
                    headerBytes[headerPos++] = buffer.readByte()
                }
                if (headerPos < 10) return false

                val magic = (headerBytes[0].toInt() and 0xFF shl 8) or (headerBytes[1].toInt() and 0xFF)
                if (magic != GzipFormat.MAGIC) {
                    throw CompressionException("Invalid gzip magic number")
                }

                headerFlags = headerBytes[3].toInt() and 0xFF
            }
        }

        return parseOptionalGzipFields(buffer, headerFlags)
    }

    private fun parseOptionalGzipFields(
        buffer: ReadBuffer,
        flags: Int,
    ): Boolean {
        // FEXTRA: 2-byte length + extra data
        if ((flags and GzipFormat.FLAG_FEXTRA) != 0) {
            while (buffer.remaining() > 0 && headerPos < 12) {
                headerBytes[headerPos++] = buffer.readByte()
            }
            if (headerPos < 12) return false

            val xlen = (headerBytes[10].toInt() and 0xFF) or ((headerBytes[11].toInt() and 0xFF) shl 8)
            repeat(xlen) {
                if (buffer.remaining() == 0) return false
                buffer.readByte()
            }
        }

        // FNAME: null-terminated string
        if ((flags and GzipFormat.FLAG_FNAME) != 0) {
            while (buffer.remaining() > 0) {
                if (buffer.readByte() == 0.toByte()) break
            }
        }

        // FCOMMENT: null-terminated string
        if ((flags and GzipFormat.FLAG_FCOMMENT) != 0) {
            while (buffer.remaining() > 0) {
                if (buffer.readByte() == 0.toByte()) break
            }
        }

        // FHCRC: 2-byte CRC16
        if ((flags and GzipFormat.FLAG_FHCRC) != 0) {
            if (buffer.remaining() < 2) return false
            buffer.readShort()
        }

        return true
    }
}
