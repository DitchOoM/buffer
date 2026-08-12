package com.ditchoom.buffer

/**
 * Configures WASM linear memory: how much is reserved up front, and how far it may grow.
 *
 * @param initialSizeMB reserved at the first allocation (default: 16MB)
 * @param maxSizeMB ceiling across all growth steps (default: 256MB)
 * @throws IllegalStateException if called after any LinearBuffer allocation
 */
actual fun PlatformBuffer.Companion.configureWasmMemory(
    initialSizeMB: Int,
    maxSizeMB: Int,
) {
    LinearMemoryAllocator.configure(initialSizeMB, maxSizeMB)
}

/**
 * Configures the initial reservation only, leaving the growth ceiling at its 256MB default.
 *
 * @param initialSizeMB reserved at the first allocation (default: 16MB)
 * @throws IllegalStateException if called after any LinearBuffer allocation
 */
@Deprecated(
    "The pool now grows on demand, so a ceiling matters more than the initial size. " +
        "Use configureWasmMemory(initialSizeMB, maxSizeMB).",
    ReplaceWith("PlatformBuffer.configureWasmMemory(initialSizeMB, 256)"),
)
actual fun PlatformBuffer.Companion.configureWasmMemory(initialSizeMB: Int) {
    LinearMemoryAllocator.configure(initialSizeMB)
}
