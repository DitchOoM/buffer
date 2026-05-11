package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default

// WasmJs: LinearBuffer (the Default) uses a bump allocator; per-instance
// memory is released as the bump pointer wraps. No leak.
internal actual val testFixtureFactory: BufferFactory = BufferFactory.Default
