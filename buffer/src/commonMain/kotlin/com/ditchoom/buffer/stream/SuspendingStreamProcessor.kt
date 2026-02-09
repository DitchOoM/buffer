package com.ditchoom.buffer.stream

import com.ditchoom.buffer.ReadBuffer

/**
 * Async variant of StreamProcessor for platforms where transforms are async-only (e.g., JS).
 *
 * All operations are suspending to allow for async decompression, decryption, etc.
 * On platforms with sync APIs, implementations can simply delegate without suspending.
 *
 * Example usage:
 * ```kotlin
 * val processor = StreamProcessor.builder(pool)
 *     .decompress(Gzip)  // extension from buffer-compression
 *     .buildSuspending()
 *
 * processor.append(chunk1)
 * processor.append(chunk2)
 * val header = processor.peekInt()
 * ```
 */
interface SuspendingStreamProcessor {
    /**
     * Appends a chunk to the processor.
     * The processor takes ownership and will free PlatformBuffers when consumed.
     */
    suspend fun append(chunk: ReadBuffer)

    /**
     * Returns total bytes available for reading across all chunks.
     */
    fun available(): Int

    /**
     * Peeks at a byte without consuming it.
     * @param offset byte offset from current position (default 0)
     */
    suspend fun peekByte(offset: Int = 0): Byte

    /**
     * Peeks at a Short without consuming it.
     * @param offset byte offset from current position (default 0)
     */
    suspend fun peekShort(offset: Int = 0): Short

    /**
     * Peeks at an Int without consuming it.
     * @param offset byte offset from current position (default 0)
     */
    suspend fun peekInt(offset: Int = 0): Int

    /**
     * Peeks at a Long without consuming it.
     * @param offset byte offset from current position (default 0)
     */
    suspend fun peekLong(offset: Int = 0): Long

    /**
     * Finds the first mismatch between stream data and the given pattern.
     * @return -1 if the patterns match completely, or the index of first mismatch
     */
    suspend fun peekMismatch(pattern: ReadBuffer): Int

    /**
     * Checks if the next bytes match the given pattern.
     */
    suspend fun peekMatches(pattern: ReadBuffer): Boolean

    /**
     * Reads a byte, consuming it.
     */
    suspend fun readByte(): Byte

    /**
     * Reads an unsigned byte (0-255), consuming it.
     */
    suspend fun readUnsignedByte(): Int

    /**
     * Reads a Short, consuming it.
     */
    suspend fun readShort(): Short

    /**
     * Reads an Int, consuming it.
     */
    suspend fun readInt(): Int

    /**
     * Reads a Long, consuming it.
     */
    suspend fun readLong(): Long

    /**
     * Reads a buffer of exactly [size] bytes.
     */
    suspend fun readBuffer(size: Int): ReadBuffer

    /**
     * Skips [count] bytes.
     */
    suspend fun skip(count: Int)

    /**
     * Signals that all data has been appended and flushes any buffered data.
     * Call this after appending all chunks but before reading final data.
     *
     * This is important for transforms that buffer data (e.g., decompression).
     * The default implementation does nothing.
     */
    suspend fun finish() {}

    /**
     * Releases all resources. Call when done processing.
     * Implicitly calls [finish] if not already called.
     */
    fun release()
}

/**
 * Default implementation that wraps a synchronous StreamProcessor.
 * Used on platforms where all operations can be sync (JVM, Native).
 */
internal class SyncToSuspendingProcessor(
    private val delegate: StreamProcessor,
) : SuspendingStreamProcessor {
    override suspend fun append(chunk: ReadBuffer) = delegate.append(chunk)

    override fun available(): Int = delegate.available()

    override suspend fun peekByte(offset: Int): Byte = delegate.peekByte(offset)

    override suspend fun peekShort(offset: Int): Short = delegate.peekShort(offset)

    override suspend fun peekInt(offset: Int): Int = delegate.peekInt(offset)

    override suspend fun peekLong(offset: Int): Long = delegate.peekLong(offset)

    override suspend fun peekMismatch(pattern: ReadBuffer): Int = delegate.peekMismatch(pattern)

    override suspend fun peekMatches(pattern: ReadBuffer): Boolean = delegate.peekMatches(pattern)

    override suspend fun readByte(): Byte = delegate.readByte()

    override suspend fun readUnsignedByte(): Int = delegate.readUnsignedByte()

    override suspend fun readShort(): Short = delegate.readShort()

    override suspend fun readInt(): Int = delegate.readInt()

    override suspend fun readLong(): Long = delegate.readLong()

    override suspend fun readBuffer(size: Int): ReadBuffer = delegate.readBuffer(size)

    override suspend fun skip(count: Int) = delegate.skip(count)

    override suspend fun finish() = delegate.finish()

    override fun release() = delegate.release()
}
