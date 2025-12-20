package com.ditchoom.buffer.jni

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.UnsafeBuffer
import com.ditchoom.buffer.withUnsafeBuffer
import java.nio.ByteBuffer

/**
 * JNI Direct Buffer Communication Example
 *
 * Demonstrates how to use UnsafeBuffer for zero-copy communication with native code.
 * This enables high-performance scenarios where:
 * - Native code writes directly to memory that Kotlin can read
 * - Kotlin writes directly to memory that native code can read
 * - No intermediate copies are needed
 *
 * Usage patterns:
 * 1. Allocate direct memory using UnsafeBuffer
 * 2. Get the memory address for passing to JNI
 * 3. Native code reads/writes at that address
 * 4. Kotlin code reads/writes using the buffer interface
 */
object JniDirectBuffer {
    /**
     * Example: Zero-copy native library communication.
     *
     * This demonstrates the pattern for sharing memory with native code.
     * Native methods would be declared like:
     *
     * ```c
     * JNIEXPORT void JNICALL Java_com_ditchoom_buffer_jni_JniDirectBuffer_nativeProcessData(
     *     JNIEnv *env, jclass clazz, jlong address, jint size) {
     *     // Direct memory access - no JNI GetByteArrayElements needed
     *     unsigned char* data = (unsigned char*)address;
     *     // Process data directly...
     * }
     * ```
     */
    fun processWithNativeLibrary(
        data: ByteArray,
        processor: (address: Long, size: Int) -> Unit,
    ): ByteArray =
        withUnsafeBuffer(data.size, ByteOrder.NATIVE) { buffer ->
            // Write data to unsafe buffer
            buffer.writeBytes(data)
            buffer.resetForRead()

            // Get the memory address for native code
            val address = getBufferAddress(buffer)
            val size = data.size

            // Native code can now directly read/write at this address
            processor(address, size)

            // Read back the (potentially modified) data
            buffer.position(0)
            buffer.readByteArray(size)
        }

    /**
     * Example: Shared memory for streaming data.
     *
     * Creates a pinned buffer that can be used for continuous data streaming
     * between Kotlin and native code without reallocation.
     */
    inline fun <R> withStreamingBuffer(
        size: Int,
        block: (buffer: UnsafeBuffer, address: Long) -> R,
    ): R =
        withUnsafeBuffer(size, ByteOrder.NATIVE) { buffer ->
            val address = getBufferAddress(buffer)
            block(buffer, address)
        }

    /**
     * Gets the native memory address of an UnsafeBuffer.
     *
     * This address can be passed to JNI native methods for direct memory access.
     * Note: The address is only valid while the buffer is in scope.
     */
    fun getBufferAddress(buffer: UnsafeBuffer): Long {
        // For JVM UnsafeBuffer backed by sun.misc.Unsafe, we need to extract the address
        // The implementation depends on how UnsafeBuffer is implemented
        return try {
            // Try to get the base address from the internal field
            val field = buffer::class.java.getDeclaredField("baseAddress")
            field.isAccessible = true
            field.getLong(buffer)
        } catch (e: Exception) {
            // Fallback: allocate a direct ByteBuffer and get its address
            0L // Return 0 to indicate address not available
        }
    }

    /**
     * Creates a direct ByteBuffer that shares memory with native code.
     *
     * This is an alternative approach using standard JNI DirectByteBuffer support.
     */
    fun createDirectBuffer(size: Int): DirectJniBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(size)
        val address = getDirectBufferAddress(byteBuffer)
        return DirectJniBuffer(byteBuffer, address, size)
    }

    /**
     * Gets the native memory address of a DirectByteBuffer.
     */
    private fun getDirectBufferAddress(buffer: ByteBuffer): Long =
        try {
            val addressField = java.nio.Buffer::class.java.getDeclaredField("address")
            addressField.isAccessible = true
            addressField.getLong(buffer)
        } catch (e: Exception) {
            0L
        }
}

/**
 * A direct buffer that can be shared with native code via JNI.
 */
class DirectJniBuffer(
    val byteBuffer: ByteBuffer,
    val address: Long,
    val size: Int,
) {
    /**
     * Writes data to the buffer.
     */
    fun write(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size,
    ) {
        byteBuffer.clear()
        byteBuffer.put(data, offset, length)
    }

    /**
     * Reads data from the buffer.
     */
    fun read(length: Int): ByteArray {
        val result = ByteArray(length)
        byteBuffer.position(0)
        byteBuffer.get(result)
        return result
    }

    /**
     * Resets the buffer for writing.
     */
    fun resetForWrite() {
        byteBuffer.clear()
    }

    /**
     * Resets the buffer for reading.
     */
    fun resetForRead() {
        byteBuffer.flip()
    }
}

/**
 * Example native method declarations (would be implemented in C/C++).
 *
 * These show the pattern for declaring JNI methods that work with direct memory.
 */
object NativeMethodsExample {
    /**
     * Processes data at the given memory address.
     *
     * Native implementation would be:
     * ```c
     * JNIEXPORT jint JNICALL Java_...processBuffer(JNIEnv *env, jclass clazz,
     *     jlong address, jint size) {
     *     uint8_t* data = (uint8_t*)address;
     *     // Direct memory manipulation here
     *     return processed_bytes;
     * }
     * ```
     */
    @JvmStatic
    external fun processBuffer(
        address: Long,
        size: Int,
    ): Int

    /**
     * Compresses data in-place at the given memory address.
     */
    @JvmStatic
    external fun compressInPlace(
        address: Long,
        inputSize: Int,
        maxOutputSize: Int,
    ): Int

    /**
     * Decompresses data from one address to another.
     */
    @JvmStatic
    external fun decompress(
        inputAddress: Long,
        inputSize: Int,
        outputAddress: Long,
        maxOutputSize: Int,
    ): Int

    /**
     * Encrypts data in-place.
     */
    @JvmStatic
    external fun encryptInPlace(
        address: Long,
        size: Int,
        keyAddress: Long,
        keySize: Int,
    ): Int

    /**
     * Computes hash of data at the given address.
     */
    @JvmStatic
    external fun computeHash(
        address: Long,
        size: Int,
        outputAddress: Long,
    )
}

/**
 * High-performance protocol parser using JNI.
 *
 * This example shows how to use native code for performance-critical
 * protocol parsing while keeping the Kotlin API clean.
 */
class JniProtocolParser(
    private val bufferSize: Int = 65536,
) {
    private val parseBuffer = JniDirectBuffer.createDirectBuffer(bufferSize)

    /**
     * Parses a protocol message using native code.
     *
     * The workflow is:
     * 1. Write incoming data to direct buffer
     * 2. Call native parser with buffer address
     * 3. Native code parses and writes results to output region
     * 4. Read parsed results from Kotlin
     */
    fun parseMessage(data: ByteArray): ParsedMessage {
        require(data.size <= bufferSize) { "Data exceeds buffer size" }

        // Write data to direct buffer
        parseBuffer.write(data)

        // In a real implementation, this would call native code:
        // val resultSize = NativeMethodsExample.processBuffer(parseBuffer.address, data.size)

        // For this example, we'll parse in Kotlin
        val header = parseBuffer.byteBuffer.getInt(0)
        val length = parseBuffer.byteBuffer.getInt(4)
        val payload = ByteArray(length.coerceAtMost(data.size - 8))
        parseBuffer.byteBuffer.position(8)
        parseBuffer.byteBuffer.get(payload)

        return ParsedMessage(header, payload)
    }

    /**
     * Cleans up resources.
     */
    fun close() {
        // DirectByteBuffer is managed by GC, but explicit cleanup could be added
    }
}

/**
 * Parsed message result.
 */
data class ParsedMessage(
    val header: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedMessage) return false
        return header == other.header && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = header
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Zero-copy ring buffer for native communication.
 *
 * This pattern is useful for streaming data between Kotlin and native code
 * without any memory copies.
 */
class NativeRingBuffer(
    capacity: Int,
) {
    private val buffer = JniDirectBuffer.createDirectBuffer(capacity)
    private var writePos = 0
    private var readPos = 0

    val address: Long get() = buffer.address
    val capacity: Int get() = buffer.size

    /**
     * Writes data to the ring buffer.
     * Returns number of bytes written.
     */
    fun write(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size,
    ): Int {
        val available = capacity - (writePos - readPos)
        val toWrite = minOf(length, available)

        if (toWrite > 0) {
            val writeIdx = writePos % capacity
            val firstChunk = minOf(toWrite, capacity - writeIdx)
            val secondChunk = toWrite - firstChunk

            buffer.byteBuffer.position(writeIdx)
            buffer.byteBuffer.put(data, offset, firstChunk)

            if (secondChunk > 0) {
                buffer.byteBuffer.position(0)
                buffer.byteBuffer.put(data, offset + firstChunk, secondChunk)
            }

            writePos += toWrite
        }

        return toWrite
    }

    /**
     * Reads data from the ring buffer.
     * Returns number of bytes read.
     */
    fun read(
        output: ByteArray,
        offset: Int = 0,
        length: Int = output.size,
    ): Int {
        val available = writePos - readPos
        val toRead = minOf(length, available)

        if (toRead > 0) {
            val readIdx = readPos % capacity
            val firstChunk = minOf(toRead, capacity - readIdx)
            val secondChunk = toRead - firstChunk

            buffer.byteBuffer.position(readIdx)
            buffer.byteBuffer.get(output, offset, firstChunk)

            if (secondChunk > 0) {
                buffer.byteBuffer.position(0)
                buffer.byteBuffer.get(output, offset + firstChunk, secondChunk)
            }

            readPos += toRead
        }

        return toRead
    }

    /**
     * Returns the number of bytes available for reading.
     */
    fun available(): Int = writePos - readPos

    /**
     * Returns the number of bytes available for writing.
     */
    fun remaining(): Int = capacity - (writePos - readPos)

    /**
     * Resets the ring buffer.
     */
    fun reset() {
        writePos = 0
        readPos = 0
    }
}
