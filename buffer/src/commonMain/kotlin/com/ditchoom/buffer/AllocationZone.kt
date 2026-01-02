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

    class Custom(
        val allocator: (Int) -> PlatformBuffer,
    ) : AllocationZone()
}

@Deprecated("Use SharedMemory")
typealias AndroidSharedMemory = AllocationZone.SharedMemory
