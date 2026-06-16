@file:Suppress("unused") // Loaded at runtime via multi-release JAR, not referenced directly

package com.ditchoom.buffer

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

// FFM implementation for Java 21+ (shadows the Unsafe version in jvmMain via the multi-release JAR).
//
// A single global-scope segment spanning all addressable memory. Because its scope is the GLOBAL
// session — which can never be closed — segment.get() carries no per-access liveness check: the JIT
// proves it always valid and folds it away, so reads compile to the same bare load as Unsafe, but
// through the supported FFM API (no sun.misc.Unsafe, no --add-opens). This is the JDK-blessed
// replacement for the Unsafe memory accessors. Layouts are native byte order; callers adjust.
private val EVERYTHING: MemorySegment = MemorySegment.NULL.reinterpret(Long.MAX_VALUE)

internal fun directGetByte(address: Long): Byte = EVERYTHING.get(ValueLayout.JAVA_BYTE, address)

internal fun directGetLong(address: Long): Long = EVERYTHING.get(ValueLayout.JAVA_LONG_UNALIGNED, address)
