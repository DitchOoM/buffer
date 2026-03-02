package com.ditchoom.buffer

interface PlatformBuffer :
    ReadWriteBuffer,
    SuspendCloseable,
    Parcelable {
    /**
     * Frees native memory resources without requiring suspend context.
     *
     * **Lifecycle responsibility:** Buffers allocated with [AllocationZone.Direct] or
     * [AllocationZone.SharedMemory] may use native memory that is NOT garbage-collected
     * on all platforms. Callers must ensure cleanup via one of:
     * - [use] block (recommended): `buffer.use { ... }`
     * - [com.ditchoom.buffer.pool.BufferPool] / `withBuffer`: pool manages lifecycle automatically
     * - Explicit call to [freeNativeMemory] or [close]
     *
     * | Platform | Direct buffer cleanup |
     * |----------|----------------------|
     * | JVM 9-20 | **Best-effort** — `Unsafe.invokeCleaner` (falls back to GC) |
     * | JVM 21+  | **Must free** — Arena-backed, not GC'd |
     * | Android  | GC-managed (no action needed) — backed by `VMRuntime.newNonMovableArray()`, `invokeCleaner` unavailable |
     * | Apple    | ARC-managed (no action needed) |
     * | Linux    | **Must free** — malloc-backed |
     * | WASM     | **Must free** — linear memory |
     * | JS       | GC-managed (no action needed) |
     *
     * Calling this on a freed buffer is safe (idempotent).
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
