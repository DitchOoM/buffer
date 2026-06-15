package com.ditchoom.buffer

// Unchecked native reads for direct buffers, addressed absolutely (the caller has already range-checked
// the window — see ReadBuffer.getUnchecked). These bypass DirectByteBuffer.get(), whose
// ScopedMemoryAccess path runs a per-access session-liveness check (MemorySessionImpl.checkValidStateRaw)
// that the JIT cannot hoist out of a hot scan loop — ~20% of a direct/mmap scan on JDK 21+.
//
// MRJAR: this sun.misc.Unsafe implementation is used on JVM 8-20 (FFM does not exist there). On JDK 21+
// it is shadowed by the FFM version in jvm21Main, which needs neither Unsafe nor any --add-opens. Both
// read in native byte order; callers adjust for the buffer's byteOrder.
internal fun directGetByte(address: Long): Byte = UnsafeMemory.getByte(address)

internal fun directGetLong(address: Long): Long = UnsafeMemory.getLong(address)
