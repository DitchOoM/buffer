package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default

// JS: JsBuffer (the Default) is JS-GC-managed — no leak.
internal actual val testFixtureFactory: BufferFactory = BufferFactory.Default
