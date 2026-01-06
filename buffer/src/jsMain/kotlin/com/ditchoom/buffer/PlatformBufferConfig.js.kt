package com.ditchoom.buffer

/**
 * No-op on JavaScript - WASM memory configuration is not applicable.
 */
actual fun PlatformBuffer.Companion.configureWasmMemory(initialSizeMB: Int) {
    // No-op: WASM memory configuration is not applicable on JavaScript
}
