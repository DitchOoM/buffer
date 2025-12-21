package com.ditchoom.buffer

@Suppress("NOTHING_TO_INLINE")
internal actual inline fun <T> withLock(
    lock: Any,
    block: () -> T,
): T = synchronized(lock, block)
