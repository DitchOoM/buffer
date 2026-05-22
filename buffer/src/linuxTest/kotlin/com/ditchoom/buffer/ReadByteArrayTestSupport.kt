package com.ditchoom.buffer

/**
 * Linux native: `NativeBuffer.readByteArray` allocates `ByteArray(size)` and
 * fills it via `UnsafeMemory.copyMemoryToArray` — copy semantics, independent
 * of the source. `ByteArrayBuffer` (used for wrapped Kotlin arrays) copies
 * via `data.copyOfRange`. Independence holds.
 */
internal actual val readByteArrayAliasesSource: Boolean = false
