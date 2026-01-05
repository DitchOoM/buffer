package com.ditchoom.buffer

/**
 * Platform-specific buffer configuration.
 *
 * Call [configureWasmMemory] at app startup to customize WASM linear memory allocation.
 * This must be called before any `PlatformBuffer.allocate()` with `AllocationZone.Direct`.
 *
 * ```kotlin
 * // At app startup:
 * PlatformBuffer.configureWasmMemory(initialSizeMB = 8)
 * ```
 *
 * On non-WASM platforms, this is a no-op.
 */
expect fun PlatformBuffer.Companion.configureWasmMemory(initialSizeMB: Int = 16)
