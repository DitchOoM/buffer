package com.ditchoom.buffer

interface PlatformBuffer :
    ReadWriteBuffer,
    SuspendCloseable,
    Parcelable {
    /**
     * Frees native memory resources without requiring suspend context.
     * No-op on JVM/JS where GC handles cleanup. On Linux, frees the underlying malloc'd memory.
     * For pool-acquired buffers, returns the buffer to its pool instead of freeing.
     */
    fun freeNativeMemory() {}

    /**
     * Returns the underlying platform buffer, unwrapping one layer of decoration.
     *
     * @see [unwrapFully] for the correct replacement that strips all wrapper layers.
     * @see [nativeMemoryAccess] and [managedMemoryAccess] for interface-based access that
     * works transparently through wrappers without downcasting.
     */
    @Deprecated(
        "unwrap() only peels one layer and requires callers to cast to PlatformBuffer first, " +
            "which breaks on TrackedSlice and other non-PlatformBuffer wrappers. " +
            "Use ReadBuffer.unwrapFully() for concrete type access, or " +
            "nativeMemoryAccess/managedMemoryAccess extensions for interface-based dispatch.",
        ReplaceWith("(this as ReadBuffer).unwrapFully()", "com.ditchoom.buffer.unwrapFully"),
    )
    fun unwrap(): PlatformBuffer = this

    companion object
}
