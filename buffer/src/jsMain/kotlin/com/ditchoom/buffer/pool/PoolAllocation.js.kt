package com.ditchoom.buffer.pool

import com.ditchoom.buffer.PlatformBuffer

// JS surfaces no catchable allocation-failure error and its buffers are engine-managed;
// nothing to reclaim, so allocate straight through.
internal actual fun BufferPool.allocateOrReclaim(allocate: () -> PlatformBuffer): PlatformBuffer = allocate()
