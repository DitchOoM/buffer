package com.ditchoom.buffer

import com.ditchoom.buffer.pool.PoolReleasable

/** Frees native memory if this is a PlatformBuffer, or releases pool ref if TrackedSlice. */
fun ReadBuffer.freeIfNeeded() {
    when (this) {
        is PlatformBuffer -> freeNativeMemory()
        is PoolReleasable -> releaseToPool()
    }
}

/** Frees all buffers in the list. */
fun List<ReadBuffer>.freeAll() {
    for (buffer in this) {
        buffer.freeIfNeeded()
    }
}
