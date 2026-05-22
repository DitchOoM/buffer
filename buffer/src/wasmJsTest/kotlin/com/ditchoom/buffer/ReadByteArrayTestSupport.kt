package com.ditchoom.buffer

/**
 * WASM: `LinearBuffer.readByteArray` copies from linear memory into a fresh
 * `ByteArray(size)` on the Wasm-GC heap (separate memory spaces — copy is
 * unavoidable). `ByteArrayBuffer` (the heap-backed sibling) copies via
 * `data.copyOfRange`. Independence holds.
 */
internal actual val readByteArrayAliasesSource: Boolean = false
