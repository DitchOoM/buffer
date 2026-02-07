package com.ditchoom.buffer

interface PlatformBuffer :
    ReadWriteBuffer,
    SuspendCloseable,
    Parcelable {
    /**
     * Frees native memory resources without requiring suspend context.
     * No-op on JVM/JS where GC handles cleanup. On Linux, frees the underlying malloc'd memory.
     * Used by pool internals where suspend is not available.
     */
    fun freeNativeMemory() {}

    companion object
}
