@file:Suppress("unused") // Loaded at runtime via multi-release JAR, not referenced directly

package com.ditchoom.buffer

import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer

/**
 * FFM-based implementation for Java 21+.
 * Uses MemorySegment.ofBuffer() instead of reflection for cleaner, safer access.
 */
internal fun getDirectBufferAddress(buffer: ByteBuffer): Long = MemorySegment.ofBuffer(buffer).address()
