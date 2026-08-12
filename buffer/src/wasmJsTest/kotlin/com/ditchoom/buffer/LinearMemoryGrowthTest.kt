package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The wasmJs half of `SustainedAllocationChurnTest`: what happens when buffers are allocated and
 * never released.
 *
 * Linear memory has no collector — it sits outside the Wasm-GC heap and a [LinearBuffer] has no
 * finalizer — so an unreleased buffer stays consumed. What used to make that fatal was a *fixed*
 * pool: [LinearMemoryAllocator] pre-reserved 256MB and threw on exhaustion, because the workaround
 * for a Kotlin/WASM optimizer bug was read as forbidding a `jsMemoryGrow` call anywhere reachable
 * from the allocation path. It only forbids one directly in `allocateOffset`'s body —
 * `initializeMemory` had always been called from there — so the pool now reserves
 * [LinearMemoryConfig.initialSizeMB] and grows from there, up to [LinearMemoryConfig.maxSizeMB].
 *
 * These tests are written to be order-independent. The pool is process-wide and never shrinks (WASM
 * has no way to return linear memory), so an earlier test in the same node process may already have
 * grown it to the ceiling. The contract that holds either way is asserted directly: a request the
 * pool cannot currently satisfy either grows it, or fails with the ceiling diagnostic — it never
 * succeeds without backing memory, and never climbs until the engine gives out.
 */
class LinearMemoryGrowthTest {
    private fun stats() = LinearMemoryAllocator.getAllocationStats()

    /** Bytes the pool could still claim before hitting [LinearMemoryConfig.maxSizeMB]. */
    private fun headroom(): Int = LinearMemoryConfig.maxSizeMB * MIB - stats().poolBytes

    /** Bytes already claimed from the engine but not yet handed out. */
    private fun freeInPool(): Int = stats().poolBytes - stats().totalAllocated

    @Test
    fun allocatingPastThePoolGrowsItOrStopsAtTheCeiling() {
        val poolBefore = stats().poolBytes
        val hadHeadroom = headroom() > 0
        // Deliberately more than the pool can currently serve, so growth is the only way through.
        val target = freeInPool() + OVERSHOOT_BYTES

        val leaked = mutableListOf<PlatformBuffer>()
        var grew = false
        try {
            var claimed = 0
            while (claimed < target) {
                leaked.add(BufferFactory.Default.allocate(CHURN_BYTES).also { it.writeByte(1) })
                claimed += CHURN_BYTES
            }
            grew = true
        } catch (_: OutOfMemoryError) {
            assertTrue(
                !hadHeadroom,
                "growth must not fail while ${headroom() / MIB} MiB of headroom remains under the " +
                    "${LinearMemoryConfig.maxSizeMB} MiB ceiling",
            )
        } finally {
            // Release in reverse: each buffer is then the top allocation, so the bump pointer
            // rewinds the whole way instead of parking every block on the free list.
            for (i in leaked.indices.reversed()) {
                leaked[i].freeNativeMemory()
            }
        }

        if (!grew) return // pool was already at the ceiling; the assertion above covered that case

        assertTrue(
            stats().poolBytes > poolBefore,
            "serving $target bytes past the pool must have grown it beyond $poolBefore",
        )
        assertTrue(
            stats().poolBytes <= LinearMemoryConfig.maxSizeMB * MIB,
            "growth must never exceed the configured ceiling",
        )

        // Everything above reads the allocator's own bookkeeping, so on its own it cannot
        // distinguish "the bytes are available again" from "the counters say they are". Re-running
        // the volume that just succeeded settles that without consulting a counter: if releasing
        // updated the arithmetic but freed nothing, this pass runs out where the first one did not.
        val reused = mutableListOf<PlatformBuffer>()
        try {
            var claimed = 0
            while (claimed < target) {
                reused.add(BufferFactory.Default.allocate(CHURN_BYTES).also { it.writeByte(1) })
                claimed += CHURN_BYTES
            }
        } catch (_: OutOfMemoryError) {
            fail("released bytes were not genuinely reusable: ran out after ${reused.size} allocations")
        } finally {
            for (i in reused.indices.reversed()) {
                reused[i].freeNativeMemory()
            }
        }
    }

    /** Everything handed out during growth must still be reclaimable afterwards. */
    @Test
    fun blocksAllocatedFromGrownMemoryAreRecoverable() {
        val watermarkBefore = stats().totalAllocated
        val size = freeInPool() + SMALL_OVERSHOOT_BYTES
        if (headroom() <= 0) return // pool already at the ceiling; nothing to grow into

        val buffer = BufferFactory.Default.allocate(size)
        try {
            buffer.writeByte(1)
            buffer.resetForRead()
            assertEquals(1, buffer.readByte(), "memory obtained by growing must be usable")
        } finally {
            buffer.freeNativeMemory()
        }
        assertTrue(
            stats().totalAllocated <= watermarkBefore,
            "a block served out of grown memory must be reclaimable like any other",
        )
    }

    private companion object {
        private const val CHURN_BYTES = 4096
        private const val MIB = 1024 * 1024

        /** Comfortably more than one [growMemory] step, so growth is unambiguous. */
        private const val OVERSHOOT_BYTES = 24 * MIB
        private const val SMALL_OVERSHOOT_BYTES = 4 * MIB
    }
}
