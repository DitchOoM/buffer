package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * JNI bridge to Android NDK functions for creating and inspecting DirectByteBuffers.
 *
 * Uses the officially supported JNI functions [NewDirectByteBuffer] and
 * [GetDirectBufferAddress] instead of reflection-based approaches that are
 * blocklisted on Android API 28+.
 */
internal object NativeBufferHelper {
    init {
        System.loadLibrary("buffer_jni")
    }

    /**
     * Creates a [java.nio.DirectByteBuffer] wrapping the given native memory address.
     *
     * Uses JNI `NewDirectByteBuffer` — the officially supported way to create
     * a DirectByteBuffer from a native address on Android.
     *
     * @param address native memory address (from [UnsafeAllocator.allocateMemory])
     * @param capacity buffer capacity in bytes
     * @return a DirectByteBuffer backed by the native memory at [address]
     */
    @JvmStatic
    external fun newDirectByteBuffer(
        address: Long,
        capacity: Int,
    ): ByteBuffer

    /**
     * Returns the native memory address of a [java.nio.DirectByteBuffer].
     *
     * Uses JNI `GetDirectBufferAddress` — the officially supported way to get
     * a DirectByteBuffer's native address on Android.
     *
     * @param buffer a direct ByteBuffer
     * @return the native memory address, or 0 if the buffer is not direct
     */
    @JvmStatic
    external fun getDirectBufferAddress(buffer: ByteBuffer): Long
}
