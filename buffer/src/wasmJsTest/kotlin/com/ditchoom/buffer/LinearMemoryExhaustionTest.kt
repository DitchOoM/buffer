package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The wasmJs half of `SustainedAllocationChurnTest`: what happens when buffers are allocated and
 * never released.
 *
 * This is the one behaviour reclamation cannot fix. Linear memory sits outside the Wasm-GC heap and
 * there is no finalization hook to attach to a [LinearBuffer], so dropping the last reference to one
 * cannot return its bytes — unlike every other target, where a collector eventually does. The pool
 * is a fixed [LinearMemoryConfig.initialSizeMB], so a caller who never releases will exhaust it.
 * Releasing is the caller's job; `SustainedAllocationChurnTest` covers that side.
 *
 * The test keeps every buffer it allocates and releases them in a `finally`, so it exercises the
 * exhaustion without leaving the shared allocator poisoned for the rest of the suite — and, in
 * doing so, proves 256 MiB of leaked blocks are fully recoverable once they are released.
 */
class LinearMemoryExhaustionTest {
    @Test
    fun unreleasedBuffersExhaustThePoolAndAreRecoverableOnRelease() {
        val baseline = LinearMemoryAllocator.getAllocationStats().totalAllocated
        val leaked = mutableListOf<PlatformBuffer>()
        try {
            assertFailsWith<OutOfMemoryError>("a fixed pool must run out when nothing is ever released") {
                repeat(ITERATIONS) {
                    leaked.add(BufferFactory.Default.allocate(CHURN_BYTES).also { buffer -> buffer.writeByte(1) })
                }
            }
            assertTrue(
                leaked.size > MIN_EXPECTED_ALLOCATIONS,
                "expected the pool to absorb most of ${LinearMemoryConfig.initialSizeMB} MB first, " +
                    "got ${leaked.size} allocations",
            )
        } finally {
            // Release in reverse: each buffer is then the top allocation, so the bump pointer rewinds
            // the whole way instead of parking 65k blocks on the free list.
            for (i in leaked.indices.reversed()) {
                leaked[i].freeNativeMemory()
            }
        }
        // `<=` rather than `==`: if an earlier test left a block of this size parked on the free
        // list, the first allocation reused it and the final rewind can legitimately go past the
        // starting watermark. What must not happen is retaining any of the 256 MiB.
        val after = LinearMemoryAllocator.getAllocationStats().totalAllocated
        assertTrue(after <= baseline, "every leaked block must be recoverable once released, got $after > $baseline")
    }

    private companion object {
        private const val CHURN_BYTES = 4096
        private const val ITERATIONS = 70_000
        private const val MIN_EXPECTED_ALLOCATIONS = 60_000
    }
}
