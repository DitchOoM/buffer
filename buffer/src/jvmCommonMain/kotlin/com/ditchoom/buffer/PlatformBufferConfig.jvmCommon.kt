package com.ditchoom.buffer

/**
 * No-op on JVM/Android - WASM memory configuration is not applicable.
 */
actual fun PlatformBuffer.Companion.configureWasmMemory(initialSizeMB: Int) {
    // No-op: WASM memory configuration is not applicable on JVM/Android
}
