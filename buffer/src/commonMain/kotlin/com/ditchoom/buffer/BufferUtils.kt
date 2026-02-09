package com.ditchoom.buffer

/** Frees native memory if this is a PlatformBuffer. No-op on JVM (GC handles it). */
fun ReadBuffer.freeIfNeeded() {
    (this as? PlatformBuffer)?.freeNativeMemory()
}

/** Frees all buffers in the list. */
fun List<ReadBuffer>.freeAll() {
    for (buffer in this) {
        buffer.freeIfNeeded()
    }
}
