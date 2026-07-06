package com.ditchoom.buffer.okio

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.nativeMemoryAccess

/** Sizes covering empty, sub-word, word-boundary, page-boundary, and multi-page cases. */
internal val ROUND_TRIP_SIZES = intArrayOf(0, 1, 7, 8, 9, 4095, 4096, 65536, 70001)

/** Deterministic, position-dependent byte pattern so misordered copies are caught. */
internal fun patternBytes(size: Int): ByteArray = ByteArray(size) { ((it * 31 + 7) and 0xFF).toByte() }

/** A [PlatformBuffer] holding [bytes], positioned for reading. */
internal fun readableBufferOf(
    bytes: ByteArray,
    factory: BufferFactory = BufferFactory.Default,
    byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
): PlatformBuffer {
    val buffer = factory.allocate(bytes.size, byteOrder)
    if (bytes.isNotEmpty()) buffer.writeBytes(bytes)
    buffer.resetForRead()
    return buffer
}

/** The default + managed factories exercise native and heap (ManagedMemoryAccess) code paths. */
internal val bridgeFactories: List<Pair<String, BufferFactory>> =
    listOf(
        "default" to BufferFactory.Default,
        "managed" to BufferFactory.managed(),
    )

/**
 * Allocates [bytes] into a [BufferFactory.Default] buffer positioned for reading, but only if
 * [BufferFactory.Default] actually yields a native ([com.ditchoom.buffer.NativeMemoryAccess])
 * backing on the current platform. Returns `null` on platforms where `Default` falls back to a
 * managed (heap) backing — e.g. Linux — so callers can skip the native-only assertion instead of
 * duplicating the managed-backing coverage that already exists elsewhere.
 */
internal fun nativeReadableBufferOrNull(bytes: ByteArray): PlatformBuffer? {
    val buffer = readableBufferOf(bytes, BufferFactory.Default)
    return if (buffer.nativeMemoryAccess != null) buffer else null
}

/**
 * Allocates a [BufferFactory.Default] buffer of [size] bytes, but only if it actually yields a
 * native backing on the current platform (see [nativeReadableBufferOrNull]).
 */
internal fun nativeAllocateOrNull(size: Int): PlatformBuffer? {
    val buffer = BufferFactory.Default.allocate(size)
    return if (buffer.nativeMemoryAccess != null) buffer else null
}
