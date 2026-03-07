package com.ditchoom.buffer

/**
 * Primary buffer interface combining read/write operations with platform lifecycle support.
 *
 * All buffer implementations on every platform implement this interface.
 *
 * ## Creation
 *
 * Use [BufferFactory] to create buffers:
 * ```kotlin
 * val buf = BufferFactory.Default.allocate(1024)
 * val wrapped = BufferFactory.Default.wrap(byteArray)
 * ```
 *
 * @see BufferFactory for buffer creation
 */
interface PlatformBuffer :
    ReadWriteBuffer,
    Parcelable {
    /**
     * Frees native memory resources.
     * No-op on platforms where GC handles cleanup (JVM, JS, Apple ARC).
     * For pool-acquired buffers, returns the buffer to its pool instead of freeing.
     */
    fun freeNativeMemory() {}

    @Deprecated(
        "unwrap() only peels one layer and requires callers to cast to PlatformBuffer first, " +
            "which breaks on TrackedSlice and other non-PlatformBuffer wrappers. " +
            "Use ReadBuffer.unwrapFully() for concrete type access, or " +
            "nativeMemoryAccess/managedMemoryAccess extensions for interface-based dispatch.",
        ReplaceWith("(this as ReadBuffer).unwrapFully()", "com.ditchoom.buffer.unwrapFully"),
    )
    fun unwrap(): PlatformBuffer = this

    companion object {
        /**
         * Allocates a buffer using platform-optimal native memory.
         * Convenience shorthand for `BufferFactory.Default.allocate(size, byteOrder)`.
         */
        fun allocate(
            size: Int,
            byteOrder: ByteOrder = ByteOrder.NATIVE,
        ): PlatformBuffer {
            require(size >= 0) { "Buffer size must be non-negative, got $size" }
            return BufferFactory.Default.allocate(size, byteOrder)
        }

        /**
         * Wraps an existing byte array in a buffer without copying.
         * Convenience shorthand for `BufferFactory.Default.wrap(array, byteOrder)`.
         */
        fun wrap(
            array: ByteArray,
            byteOrder: ByteOrder = ByteOrder.NATIVE,
        ): PlatformBuffer = BufferFactory.Default.wrap(array, byteOrder)
    }
}
