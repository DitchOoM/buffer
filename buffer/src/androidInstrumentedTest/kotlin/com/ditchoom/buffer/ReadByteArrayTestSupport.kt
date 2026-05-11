package com.ditchoom.buffer

/**
 * Android instrumented tests run on a real device/emulator where
 * `BaseJvmBuffer.readByteArray` copies via `System.arraycopy` (Android's
 * `DirectByteBuffer` is `MemoryRef`-backed with `hasArray() == true`, so
 * the heap-branch in `toArray` fires). The returned `ByteArray(size)` is
 * always independently allocated.
 */
internal actual val readByteArrayAliasesSource: Boolean = false
