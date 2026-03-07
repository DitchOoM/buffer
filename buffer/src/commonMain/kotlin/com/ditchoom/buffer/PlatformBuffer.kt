package com.ditchoom.buffer

/**
 * Primary buffer interface combining read/write operations with platform lifecycle support.
 *
 * `PlatformBuffer` extends [Buffer] with Android [Parcelable] support.
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
 * @see Buffer for the minimal buffer supertype
 */
interface PlatformBuffer :
    Buffer,
    Parcelable {
    @Deprecated(
        "unwrap() only peels one layer and requires callers to cast to PlatformBuffer first, " +
            "which breaks on TrackedSlice and other non-PlatformBuffer wrappers. " +
            "Use ReadBuffer.unwrapFully() for concrete type access, or " +
            "nativeMemoryAccess/managedMemoryAccess extensions for interface-based dispatch.",
        ReplaceWith("(this as ReadBuffer).unwrapFully()", "com.ditchoom.buffer.unwrapFully"),
    )
    override fun unwrap(): PlatformBuffer = this

    companion object
}
