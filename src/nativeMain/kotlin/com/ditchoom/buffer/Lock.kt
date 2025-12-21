package com.ditchoom.buffer

// Native: Using no-op for now. Could use kotlinx.atomicfu if thread-safety needed.
@Suppress("NOTHING_TO_INLINE")
internal actual inline fun <T> withLock(
    lock: Any,
    block: () -> T,
): T = block()
