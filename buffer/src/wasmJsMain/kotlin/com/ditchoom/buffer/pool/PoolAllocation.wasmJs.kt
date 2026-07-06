package com.ditchoom.buffer.pool

import com.ditchoom.buffer.PlatformBuffer

// Wasm/JS surface no catchable allocation-failure error and their buffers are
// engine-managed; nothing to reclaim, so allocate straight through.
internal actual fun BufferPool.allocateOrReclaim(allocate: () -> PlatformBuffer): PlatformBuffer = allocate()
