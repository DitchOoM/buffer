package com.ditchoom.buffer

sealed class AllocationZone {
    /** Allocates a buffer in the kotlin managed heap.*/
    data object Heap : AllocationZone()

    /** Allocates a direct buffer in native memory.*/
    data object Direct : AllocationZone()

    /**
     * Allocates a direct buffer in native shared memory.
     * In Javascript it will allocate a SharedArrayBuffer
     * Requires Android API 27, Oreo MR1. Otherwise defaults to [Direct].
     ***/
    data object SharedMemory : AllocationZone()
}

@Deprecated("Use SharedMemory")
typealias AndroidSharedMemory = AllocationZone.SharedMemory
