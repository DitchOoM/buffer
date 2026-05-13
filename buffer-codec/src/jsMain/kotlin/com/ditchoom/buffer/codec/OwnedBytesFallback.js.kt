package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default

/**
 * JS fallback for [ownedBytesFallbackFactory]. Returns [BufferFactory.Default]
 * — `JsBuffer` is backed by an `Int8Array` over `ArrayBuffer` and is
 * GC-reclaimed when the [OwnedBytesHandle] reference drops.
 */
actual fun ownedBytesFallbackFactory(): BufferFactory = BufferFactory.Default
