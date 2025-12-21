package com.ditchoom.buffer

/**
 * Cross-platform lock abstraction.
 * On JVM/Android: Uses synchronized
 * On JS/WASM: No-op (single-threaded)
 * On Native: No-op (could use atomics if needed)
 */
internal expect inline fun <T> withLock(
    lock: Any,
    block: () -> T,
): T
