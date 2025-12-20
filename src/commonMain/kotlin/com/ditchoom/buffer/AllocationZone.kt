package com.ditchoom.buffer

sealed class AllocationZone {
    /** Allocates a buffer in the java heap.*/
    object Heap : AllocationZone()

    /** Allocates a direct buffer in native memory.*/
    object Direct : AllocationZone()

    /**
     * Allocates a direct buffer in native shared memory.
     * In Javascript it will allocate a SharedArrayBuffer
     * Requires Android API 27, Oreo MR1. Otherwise defaults to [Direct].
     ***/
    object SharedMemory : AllocationZone()

    /**
     * Allocates a buffer using sun.misc.Unsafe for maximum allocation performance.
     * JVM only. On other platforms, falls back to [Direct].
     * Warning: Requires manual memory management via close(). Memory leaks if not closed.
     */
    object Unsafe : AllocationZone()

    class Custom(
        val allocator: (Int) -> PlatformBuffer,
    ) : AllocationZone()
}

@Deprecated("Use SharedMemory")
typealias AndroidSharedMemory = AllocationZone.SharedMemory
