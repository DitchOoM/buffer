package com.ditchoom.buffer.okio

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.managed

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
