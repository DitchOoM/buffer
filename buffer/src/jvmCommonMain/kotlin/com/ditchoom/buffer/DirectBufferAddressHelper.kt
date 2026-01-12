package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * Platform-specific helper for getting the native address of a direct ByteBuffer.
 *
 * Platform implementations:
 * - JVM: Cached reflection Field lookup
 * - Android: MethodHandle on API 33+, cached reflection fallback on older versions
 */
internal expect fun getDirectBufferAddress(buffer: ByteBuffer): Long
