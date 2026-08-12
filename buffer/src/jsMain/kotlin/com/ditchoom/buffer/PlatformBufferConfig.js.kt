package com.ditchoom.buffer

/**
 * No-op on JavaScript - WASM memory configuration is not applicable.
 */
actual fun PlatformBuffer.Companion.configureWasmMemory(
    initialSizeMB: Int,
    maxSizeMB: Int,
) {
    // No-op: WASM memory configuration is not applicable on JavaScript
}

/**
 * No-op on JavaScript - WASM memory configuration is not applicable.
 */
@Deprecated(
    "The pool now grows on demand, so a ceiling matters more than the initial size. " +
        "Use configureWasmMemory(initialSizeMB, maxSizeMB).",
    ReplaceWith("PlatformBuffer.configureWasmMemory(initialSizeMB, 256)"),
)
actual fun PlatformBuffer.Companion.configureWasmMemory(initialSizeMB: Int) {
    // No-op: WASM memory configuration is not applicable on JavaScript
}
