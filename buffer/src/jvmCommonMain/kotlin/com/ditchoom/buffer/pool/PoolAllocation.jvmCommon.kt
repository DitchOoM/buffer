package com.ditchoom.buffer.pool

import com.ditchoom.buffer.PlatformBuffer

internal actual fun BufferPool.allocateOrReclaim(allocate: () -> PlatformBuffer): PlatformBuffer =
    try {
        allocate()
    } catch (_: OutOfMemoryError) {
        clear()
        // Let the DirectByteBuffer cleaner reclaim the off-heap memory that clear() just
        // made unreachable — on ART there is no cleaner hook, so dropping the reference
        // alone frees nothing (see ANDROID_ART_ALLOCATOR.md). This deliberate GC hint is
        // the whole point of the recovery path, hence the suppression.
        @Suppress("ExplicitGarbageCollectionCall")
        System.gc()
        // Second attempt after reclamation; a repeat OutOfMemoryError propagates to the caller.
        allocate()
    }
