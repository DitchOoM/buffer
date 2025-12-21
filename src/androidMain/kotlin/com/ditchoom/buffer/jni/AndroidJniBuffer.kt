package com.ditchoom.buffer.jni

import android.annotation.SuppressLint
import android.os.SharedMemory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.withUnsafeBuffer
import java.nio.ByteBuffer

/**
 * Android-specific JNI and IPC buffer examples.
 *
 * Demonstrates:
 * 1. Direct memory access for native library communication
 * 2. SharedMemory for zero-copy IPC between processes
 * 3. Binder integration for Parcelable buffers
 */
object AndroidJniBuffer {
    /**
     * Creates a SharedMemory region that can be passed to other processes.
     *
     * This enables zero-copy IPC where:
     * - One process writes to the shared memory
     * - Another process reads without any data copy
     *
     * Example usage:
     * ```kotlin
     * val sharedBuffer = AndroidJniBuffer.createSharedBuffer("my_buffer", 4096)
     *
     * // In sender process:
     * sharedBuffer.writeBytes(data)
     *
     * // Pass the ParcelFileDescriptor to another process via Binder
     * // parcel.writeParcelable(sharedBuffer.sharedMemory, 0)
     *
     * // In receiver process:
     * val received = sharedBuffer.readBytes(length)
     * ```
     */
    @SuppressLint("NewApi")
    fun createSharedBuffer(
        name: String,
        size: Int,
    ): SharedIpcBuffer {
        val sharedMemory = SharedMemory.create(name, size)
        return SharedIpcBuffer(sharedMemory, size)
    }

    /**
     * Example: High-performance camera/video frame processing.
     *
     * This pattern is used for processing video frames with native code
     * while minimizing memory copies.
     */
    fun processVideoFrame(
        frameData: ByteArray,
        width: Int,
        height: Int,
        nativeProcessor: (address: Long, width: Int, height: Int) -> Unit,
    ): ByteArray =
        withUnsafeBuffer(frameData.size, ByteOrder.LITTLE_ENDIAN) { buffer ->
            buffer.writeBytes(frameData)
            buffer.resetForRead()

            // Get memory address for native processing
            val address = getBufferAddress(buffer)
            nativeProcessor(address, width, height)

            // Read processed frame
            buffer.position(0)
            buffer.readByteArray(frameData.size)
        }

    /**
     * Example: Audio buffer for native audio processing.
     *
     * Demonstrates lock-free audio buffer exchange pattern.
     */
    class NativeAudioBuffer(
        val sampleRate: Int,
        val channelCount: Int,
        bufferDurationMs: Int,
    ) {
        private val samplesPerBuffer = (sampleRate * bufferDurationMs / 1000) * channelCount
        private val byteBuffer = ByteBuffer.allocateDirect(samplesPerBuffer * 2) // 16-bit samples
        val address: Long = getDirectBufferAddress(byteBuffer)
        val size: Int = byteBuffer.capacity()

        /**
         * Writes audio samples to the buffer.
         */
        fun writeSamples(samples: ShortArray) {
            byteBuffer.clear()
            for (sample in samples) {
                byteBuffer.putShort(sample)
            }
        }

        /**
         * Reads audio samples from the buffer (after native processing).
         */
        fun readSamples(): ShortArray {
            byteBuffer.rewind()
            val samples = ShortArray(samplesPerBuffer)
            for (i in samples.indices) {
                samples[i] = byteBuffer.short
            }
            return samples
        }
    }

    private fun getBufferAddress(buffer: com.ditchoom.buffer.UnsafeBuffer): Long =
        try {
            val field = buffer::class.java.getDeclaredField("baseAddress")
            field.isAccessible = true
            field.getLong(buffer)
        } catch (e: Exception) {
            0L
        }

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
 * Shared memory buffer for Android IPC.
 *
 * Uses Android's SharedMemory API for zero-copy inter-process communication.
 */
@SuppressLint("NewApi")
class SharedIpcBuffer(
    val sharedMemory: SharedMemory,
    val size: Int,
) {
    private var byteBuffer: ByteBuffer? = null

    /**
     * Maps the shared memory for reading and writing.
     */
    fun map(): ByteBuffer {
        if (byteBuffer == null) {
            byteBuffer = sharedMemory.mapReadWrite()
        }
        return byteBuffer!!
    }

    /**
     * Writes bytes to the shared memory.
     */
    fun writeBytes(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size,
    ) {
        val buffer = map()
        buffer.clear()
        buffer.put(data, offset, length)
    }

    /**
     * Reads bytes from the shared memory.
     */
    fun readBytes(length: Int): ByteArray {
        val buffer = map()
        buffer.rewind()
        val result = ByteArray(length)
        buffer.get(result)
        return result
    }

    /**
     * Gets the native memory address for JNI access.
     */
    fun getAddress(): Long {
        val buffer = map()
        return try {
            val addressField = java.nio.Buffer::class.java.getDeclaredField("address")
            addressField.isAccessible = true
            addressField.getLong(buffer)
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Unmaps the shared memory.
     */
    fun unmap() {
        byteBuffer?.let {
            SharedMemory.unmap(it)
            byteBuffer = null
        }
    }

    /**
     * Closes the shared memory.
     */
    fun close() {
        unmap()
        sharedMemory.close()
    }
}

/**
 * Native method declarations for Android-specific operations.
 */
object AndroidNativeMethods {
    /**
     * Processes camera frame at the given memory address.
     */
    @JvmStatic
    external fun processCameraFrame(
        inputAddress: Long,
        width: Int,
        height: Int,
        outputAddress: Long,
    )

    /**
     * Applies video filter at the given memory address.
     */
    @JvmStatic
    external fun applyVideoFilter(
        address: Long,
        width: Int,
        height: Int,
        filterType: Int,
    )

    /**
     * Processes audio buffer at the given memory address.
     */
    @JvmStatic
    external fun processAudioBuffer(
        address: Long,
        sampleCount: Int,
        channelCount: Int,
        effectType: Int,
    )

    /**
     * Encodes video frame to codec-specific format.
     */
    @JvmStatic
    external fun encodeVideoFrame(
        inputAddress: Long,
        inputSize: Int,
        outputAddress: Long,
        maxOutputSize: Int,
        codecType: Int,
    ): Int

    /**
     * Decodes audio frame from codec-specific format.
     */
    @JvmStatic
    external fun decodeAudioFrame(
        inputAddress: Long,
        inputSize: Int,
        outputAddress: Long,
        maxOutputSize: Int,
        codecType: Int,
    ): Int
}
