package com.ditchoom.buffer

// WASM JS is single-threaded, no locking needed
@Suppress("NOTHING_TO_INLINE")
internal actual inline fun <T> withLock(
    lock: Any,
    block: () -> T,
): T = block()
