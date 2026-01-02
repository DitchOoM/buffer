package com.ditchoom.buffer.compression

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.StreamProcessorBuilder
import com.ditchoom.buffer.stream.SuspendingStreamProcessor
import com.ditchoom.buffer.stream.TransformSpec

/**
 * Adds decompression to the StreamProcessor pipeline.
 *
 * Usage:
 * ```kotlin
 * val processor = StreamProcessor.builder(pool)
 *     .decompress(CompressionAlgorithm.Gzip)
 *     .build()
 *
 * processor.append(compressedChunk1)
 * processor.append(compressedChunk2)
 * val data = processor.readBuffer(processor.available())
 * ```
 *
 * @param algorithm The compression algorithm to decompress (default: Gzip)
 * @param allocator Buffer allocation strategy (default: Direct)
 */
fun StreamProcessorBuilder.decompress(
    algorithm: CompressionAlgorithm = CompressionAlgorithm.Gzip,
    allocator: BufferAllocator = BufferAllocator.Default,
): StreamProcessorBuilder = addTransform(DecompressionSpec(algorithm, allocator))

/**
 * TransformSpec implementation for decompression.
 */
internal class DecompressionSpec(
    private val algorithm: CompressionAlgorithm,
    private val allocator: BufferAllocator,
) : TransformSpec {
    override fun wrapSync(inner: StreamProcessor): StreamProcessor = DecompressingStreamProcessor(inner, algorithm, allocator)

    override fun wrapSuspending(inner: SuspendingStreamProcessor): SuspendingStreamProcessor =
        SuspendingDecompressingStreamProcessor(inner, algorithm, allocator)
}

/**
 * StreamProcessor wrapper that decompresses data as it's appended.
 *
 * Usage:
 * ```kotlin
 * processor.append(compressedChunk1)
 * processor.append(compressedChunk2)
 * processor.finish()  // Signal no more data, flush buffers
 * val data = processor.readBuffer(processor.available())
 * processor.release()
 * ```
 *
 * Or use [appendLast] for the final chunk which auto-finishes:
 * ```kotlin
 * processor.append(compressedChunk1)
 * processor.appendLast(compressedChunk2)  // Appends and finishes
 * val data = processor.readBuffer(processor.available())
 * ```
 */
internal class DecompressingStreamProcessor(
    private val inner: StreamProcessor,
    algorithm: CompressionAlgorithm,
    allocator: BufferAllocator,
) : StreamProcessor by inner {
    private val decompressor =
        StreamingDecompressor.create(
            algorithm = algorithm,
            allocator = allocator,
        )
    private var finished = false

    override fun append(chunk: ReadBuffer) {
        require(!finished) { "Cannot append after finish()" }
        decompressor.decompress(chunk) { decompressedChunk ->
            inner.append(decompressedChunk)
        }
    }

    /**
     * Appends the final chunk of compressed data and finishes decompression.
     * After this call, all decompressed data is available for reading.
     */
    fun appendLast(chunk: ReadBuffer) {
        append(chunk)
        finish()
    }

    override fun finish() {
        if (!finished) {
            decompressor.finish { decompressedChunk ->
                inner.append(decompressedChunk)
            }
            finished = true
        }
    }

    override fun release() {
        if (!finished) finish()
        decompressor.close()
        inner.release()
    }
}

/**
 * Suspending StreamProcessor wrapper that decompresses data as it's appended.
 * Works on all platforms including browser JavaScript.
 *
 * Usage:
 * ```kotlin
 * processor.append(compressedChunk1)
 * processor.append(compressedChunk2)
 * processor.finish()  // Signal no more data, flush buffers
 * val data = processor.readBuffer(processor.available())
 * processor.release()
 * ```
 */
internal class SuspendingDecompressingStreamProcessor(
    private val inner: SuspendingStreamProcessor,
    algorithm: CompressionAlgorithm,
    allocator: BufferAllocator,
) : SuspendingStreamProcessor {
    private val decompressor =
        SuspendingStreamingDecompressor.create(
            algorithm = algorithm,
            allocator = allocator,
        )
    private var finished = false

    override suspend fun append(chunk: ReadBuffer) {
        require(!finished) { "Cannot append after finish()" }
        val decompressedChunks = decompressor.decompress(chunk)
        for (decompressed in decompressedChunks) {
            inner.append(decompressed)
        }
    }

    /**
     * Appends the final chunk of compressed data and finishes decompression.
     * After this call, all decompressed data is available for reading.
     */
    suspend fun appendLast(chunk: ReadBuffer) {
        append(chunk)
        finish()
    }

    override suspend fun finish() {
        if (!finished) {
            val finalChunks = decompressor.finish()
            for (chunk in finalChunks) {
                inner.append(chunk)
            }
            finished = true
        }
    }

    override fun available(): Int = inner.available()

    override suspend fun peekByte(offset: Int): Byte = inner.peekByte(offset)

    override suspend fun peekShort(offset: Int): Short = inner.peekShort(offset)

    override suspend fun peekInt(offset: Int): Int = inner.peekInt(offset)

    override suspend fun peekLong(offset: Int): Long = inner.peekLong(offset)

    override suspend fun peekMismatch(pattern: ReadBuffer): Int = inner.peekMismatch(pattern)

    override suspend fun peekMatches(pattern: ReadBuffer): Boolean = inner.peekMatches(pattern)

    override suspend fun readByte(): Byte = inner.readByte()

    override suspend fun readUnsignedByte(): Int = inner.readUnsignedByte()

    override suspend fun readShort(): Short = inner.readShort()

    override suspend fun readInt(): Int = inner.readInt()

    override suspend fun readLong(): Long = inner.readLong()

    override suspend fun readBuffer(size: Int): ReadBuffer = inner.readBuffer(size)

    override suspend fun skip(count: Int) = inner.skip(count)

    override fun release() {
        decompressor.close()
        inner.release()
    }
}
