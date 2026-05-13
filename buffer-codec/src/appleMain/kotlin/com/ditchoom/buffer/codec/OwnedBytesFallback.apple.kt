package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default

/**
 * Apple (iOS / macOS / watchOS / tvOS) fallback for
 * [ownedBytesFallbackFactory]. Returns [BufferFactory.Default] —
 * `MutableDataBuffer` wraps `NSMutableData`, which is ARC-managed; the
 * underlying native memory is freed when the [OwnedBytesHandle] reference
 * drops. No leak.
 */
actual fun ownedBytesFallbackFactory(): BufferFactory = BufferFactory.Default
