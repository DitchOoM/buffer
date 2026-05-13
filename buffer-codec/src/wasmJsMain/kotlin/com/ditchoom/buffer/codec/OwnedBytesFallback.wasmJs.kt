package com.ditchoom.buffer.codec

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default

/**
 * WasmJs fallback for [ownedBytesFallbackFactory]. Returns
 * [BufferFactory.Default] — `LinearBuffer` is allocated from the WASM
 * linear-memory pool and reclaimed by the WASM/JS GC when the
 * [OwnedBytesHandle] reference drops.
 */
actual fun ownedBytesFallbackFactory(): BufferFactory = BufferFactory.Default
