package com.ditchoom.buffer

/**
 * Minimal buffer supertype providing lifecycle and unwrap operations.
 *
 * Most code should use [PlatformBuffer], which is the primary buffer interface
 * implemented by all buffer types on every platform. `Buffer` exists as a minimal
 * supertype that [PlatformBuffer] extends.
 *
 * @see PlatformBuffer The primary interface for working with buffers
 * @see BufferFactory for buffer creation
 */
interface Buffer : ReadWriteBuffer {
    /**
     * Frees native memory resources.
     * No-op on platforms where GC handles cleanup (JVM, JS, Apple ARC).
     * For pool-acquired buffers, returns the buffer to its pool instead of freeing.
     */
    fun freeNativeMemory() {}

    /**
     * Returns the underlying platform buffer, unwrapping one layer of decoration.
     *
     * @see [unwrapFully] for the correct replacement that strips all wrapper layers.
     * @see [nativeMemoryAccess] and [managedMemoryAccess] extensions for interface-based
     * dispatch that works transparently through wrappers without downcasting.
     */
    @Deprecated(
        "unwrap() only peels one layer and requires callers to cast to Buffer first, " +
            "which breaks on TrackedSlice and other non-Buffer wrappers. " +
            "Use ReadBuffer.unwrapFully() for concrete type access, or " +
            "nativeMemoryAccess/managedMemoryAccess extensions for interface-based dispatch.",
        ReplaceWith("(this as ReadBuffer).unwrapFully()", "com.ditchoom.buffer.unwrapFully"),
    )
    fun unwrap(): Buffer = this

    companion object
}
