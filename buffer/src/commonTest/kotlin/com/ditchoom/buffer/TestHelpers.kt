package com.ditchoom.buffer

/**
 * Attempts to allocate a deterministic buffer, returning null if unsupported.
 *
 * Deterministic buffers require Unsafe DirectByteBuffer wrapping which isn't
 * available on Android host JVM unit tests. Real Android validation runs on
 * the emulator via [DeterministicBufferAndroidTest].
 */
internal fun tryDeterministicAllocate(
    size: Int,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): PlatformBuffer? =
    try {
        BufferFactory.deterministic().allocate(size, byteOrder)
    } catch (_: UnsupportedOperationException) {
        null
    }
