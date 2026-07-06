package com.ditchoom.buffer.pool

import com.ditchoom.buffer.PlatformBuffer

/**
 * Allocates via [allocate] with a one-shot recovery from allocation failure.
 *
 * On platforms whose factory can throw [OutOfMemoryError] under allocator/heap pressure
 * (JVM/Android and Kotlin/Native), the actual implementation drains this pool's cached
 * buffers ([clear]) — on JVM/Android also hinting a GC cycle so the `DirectByteBuffer`
 * cleaner can free the now-unreachable off-heap store — and retries exactly once. A second
 * consecutive failure propagates. The motivating case is Android/ART heap fragmentation
 * under sustained buffer churn, where a large allocation fails while pooled buffers still
 * pin reclaimable space (see ANDROID_ART_ALLOCATOR.md).
 *
 * On JS/Wasm this is a straight passthrough: those runtimes surface no catchable
 * allocation-failure error and their buffers are engine-managed, so there is nothing to
 * reclaim or retry.
 */
internal expect fun BufferPool.allocateOrReclaim(allocate: () -> PlatformBuffer): PlatformBuffer
