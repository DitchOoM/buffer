package com.ditchoom.buffer.codec.test.protocols.payload

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default

// JVM: DirectJvmBuffer (the Default) is cleaner-managed — no leak.
internal actual val testFixtureFactory: BufferFactory = BufferFactory.Default
