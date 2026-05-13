package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default

/**
 * JVM + Android fallback for [ownedBytesFallbackFactory]. Returns
 * [BufferFactory.Default] — direct `ByteBuffer` allocations are GC-reclaimed
 * when the [OwnedBytesHandle] reference drops, so no manual cleanup needed.
 */
actual fun ownedBytesFallbackFactory(): BufferFactory = BufferFactory.Default
