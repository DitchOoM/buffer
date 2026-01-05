package com.ditchoom.buffer

/**
 * No-op on native platforms - WASM memory configuration is not applicable.
 */
actual fun PlatformBuffer.Companion.configureWasmMemory(initialSizeMB: Int) {
    // No-op: WASM memory configuration is not applicable on native platforms
}
