package com.ditchoom.buffer

/**
 * Platform-specific buffer configuration.
 *
 * Call [configureWasmMemory] at app startup to size WASM linear memory. It must run before the first
 * direct allocation — once the pool is initialized the settings are fixed for the process.
 *
 * ```kotlin
 * // At app startup:
 * PlatformBuffer.configureWasmMemory(initialSizeMB = 8, maxSizeMB = 512)
 * ```
 *
 * [initialSizeMB] is reserved up front; the pool grows from there in steps as needed, so it is a
 * starting point rather than a ceiling. [maxSizeMB] is the ceiling: growth stops there and allocation
 * fails with a diagnostic, instead of climbing until the engine gives out and takes the page with it.
 * Raise it for workloads that genuinely hold more than 256MB of direct buffers at once; lower it to
 * surface an unreleased-buffer leak sooner.
 *
 * On non-WASM platforms, this is a no-op.
 */
expect fun PlatformBuffer.Companion.configureWasmMemory(
    initialSizeMB: Int,
    maxSizeMB: Int,
)

/**
 * Sizes WASM linear memory without setting a growth ceiling, leaving it at the 256MB default.
 *
 * Kept for source and binary compatibility with callers written when the pool could not grow and
 * [initialSizeMB] was therefore also the hard limit. It still does the right thing — the ceiling it
 * leaves in place is exactly the one the fixed pool used to impose — but it cannot express the
 * ceiling, which is the half worth tuning now that the pool grows on demand.
 *
 * Deliberately a separate overload rather than a defaulted parameter on the two-argument form: a
 * Kotlin default argument is not an overload, so widening the original declaration in place would
 * have removed `configureWasmMemory(int)` from the ABI and broken already-compiled callers.
 */
@Deprecated(
    "The pool now grows on demand, so a ceiling matters more than the initial size. " +
        "Use configureWasmMemory(initialSizeMB, maxSizeMB).",
    ReplaceWith("PlatformBuffer.configureWasmMemory(initialSizeMB, 256)"),
)
expect fun PlatformBuffer.Companion.configureWasmMemory(initialSizeMB: Int = 16)
