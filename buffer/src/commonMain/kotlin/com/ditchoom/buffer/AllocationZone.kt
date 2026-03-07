@file:Suppress("DEPRECATION")

package com.ditchoom.buffer

/**
 * Legacy allocation strategy enum. Use [BufferFactory] presets instead.
 *
 * Migration:
 * - `AllocationZone.Heap` → `BufferFactory.managed()`
 * - `AllocationZone.Direct` → `BufferFactory.Default`
 * - `AllocationZone.SharedMemory` → `BufferFactory.shared()`
 *
 * @see BufferFactory The recommended replacement
 */
@Deprecated(
    "Use BufferFactory presets instead. " +
        "AllocationZone.Heap → BufferFactory.managed(), " +
        "AllocationZone.Direct → BufferFactory.Default, " +
        "AllocationZone.SharedMemory → BufferFactory.shared().",
    level = DeprecationLevel.WARNING,
)
sealed class AllocationZone {
    /** Allocates a buffer in the kotlin managed heap. Use [BufferFactory.managed] instead. */
    @Deprecated(
        "Use BufferFactory.managed().allocate(size) instead",
        ReplaceWith("BufferFactory.managed()", "com.ditchoom.buffer.BufferFactory"),
    )
    data object Heap : AllocationZone()

    /** Allocates a direct buffer in native memory. Use [BufferFactory.Default] instead. */
    @Deprecated(
        "Use BufferFactory.Default.allocate(size) instead",
        ReplaceWith("BufferFactory.Default", "com.ditchoom.buffer.BufferFactory"),
    )
    data object Direct : AllocationZone()

    /**
     * Allocates a direct buffer in native shared memory.
     * Use [BufferFactory.shared] instead.
     */
    @Deprecated(
        "Use BufferFactory.shared().allocate(size) instead",
        ReplaceWith("BufferFactory.shared()", "com.ditchoom.buffer.BufferFactory"),
    )
    data object SharedMemory : AllocationZone()
}

@Deprecated("Use BufferFactory.shared() instead")
typealias AndroidSharedMemory = AllocationZone.SharedMemory
