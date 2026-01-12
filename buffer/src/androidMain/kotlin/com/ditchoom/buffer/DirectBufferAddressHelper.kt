package com.ditchoom.buffer

import java.nio.ByteBuffer

/**
 * Android implementation using MethodHandle on API 33+, reflection fallback otherwise.
 */
internal actual fun getDirectBufferAddress(buffer: ByteBuffer): Long = AndroidBufferHelper.getDirectBufferAddress(buffer)
