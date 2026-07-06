package com.ditchoom.buffer.pool

import com.ditchoom.buffer.PlatformBuffer

internal actual fun BufferPool.allocateOrReclaim(allocate: () -> PlatformBuffer): PlatformBuffer =
    try {
        allocate()
    } catch (_: OutOfMemoryError) {
        // K/N frees native memory eagerly on drop, so clearing the pool returns it to the
        // allocator directly — no GC hint needed. Retry once; a repeat failure propagates.
        clear()
        allocate()
    }
