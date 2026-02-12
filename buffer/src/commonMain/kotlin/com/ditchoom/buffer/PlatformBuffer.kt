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
     * Returns the underlying platform buffer, unwrapping any decorators (e.g. [PooledBuffer][com.ditchoom.buffer.pool.PooledBuffer]).
     * For non-wrapped buffers, returns itself.
     * Use this when you need to downcast to a platform-specific type (e.g. BaseJvmBuffer, NativeBuffer).
     */
    fun unwrap(): PlatformBuffer = this

    companion object
}
