package com.ditchoom.buffer

/**
 * Configures WASM linear memory allocation size.
 *
 * @param initialSizeMB Initial memory size in megabytes (default: 16MB)
 * @throws IllegalStateException if called after any LinearBuffer allocation
 */
actual fun PlatformBuffer.Companion.configureWasmMemory(initialSizeMB: Int) {
    LinearMemoryAllocator.configure(initialSizeMB)
}
