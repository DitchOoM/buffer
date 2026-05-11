package com.ditchoom.buffer

/**
 * Android unit tests use the same `BaseJvmBuffer.readByteArray` code path as
 * desktop JVM, copying into a fresh `ByteArray(size)`. Independence holds
 * even though Android's `DirectByteBuffer` takes the `hasArray() == true`
 * branch (MemoryRef-backed) — the copy still goes through `System.arraycopy`
 * into a fresh destination.
 */
internal actual val readByteArrayAliasesSource: Boolean = false
