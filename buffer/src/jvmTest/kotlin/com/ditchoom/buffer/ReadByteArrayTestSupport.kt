package com.ditchoom.buffer

/**
 * JVM desktop: `BaseJvmBuffer.readByteArray` copies via `System.arraycopy`
 * (heap branch) or `ByteBuffer.get(byte[])` (direct branch) into a freshly
 * allocated `ByteArray(size)`. The returned array is independent of the
 * source buffer's storage.
 */
internal actual val readByteArrayAliasesSource: Boolean = false
