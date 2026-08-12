@file:OptIn(ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Grows WASM linear memory directly, bypassing [LinearMemoryAllocator], so a subsequent pool growth
 * receives a grant that is **not** adjacent to the pool.
 */
@Suppress("MaxLineLength")
@JsFun("(pages) => { if (typeof wasmExports !== 'undefined' && wasmExports.memory) { return wasmExports.memory.grow(pages); } return -1; }")
private external fun foreignMemoryGrow(pages: Int): Int

/**
 * Covers [LinearMemoryAllocator]'s non-contiguous growth branch.
 *
 * When the pool grows it normally receives pages adjacent to the memory it already owns, and simply
 * extends its end pointer. If something else grew linear memory in between, the grant lands past a
 * gap the allocator does not own — so it re-bases onto the new region instead of spanning it, since
 * `reset` zeroes `[heapBase, nextOffset)` and `free` bounds-checks against `heapBase`, and neither
 * may reach across foreign memory.
 *
 * Nothing in the library does that today, which is exactly why this branch needs a test that forces
 * it: it would otherwise be the one path in the allocator that has never executed. The test grows
 * memory itself, then makes the pool grow across the gap it created.
 */
class LinearMemoryForeignGrowthTest {
    private fun stats() = LinearMemoryAllocator.getAllocationStats()

    private fun headroom(): Int = LinearMemoryConfig.maxSizeMB * MIB - stats().poolBytes

    private fun freeInPool(): Int = stats().poolBytes - stats().totalAllocated

    @Test
    fun growthAcrossForeignPagesStaysConsistent() {
        if (headroom() <= FOREIGN_PAGES * PAGE_SIZE + SLACK_BYTES) return // no room to demonstrate it

        // A buffer taken before the gap opens must survive the re-base intact.
        val beforeGap = BufferFactory.Default.allocate(PROBE_SIZE)
        beforeGap.writeInt(SENTINEL)

        // Open a gap the allocator knows nothing about.
        assertTrue(foreignMemoryGrow(FOREIGN_PAGES) >= 0, "test setup: could not grow linear memory")

        // Force the pool to grow; the grant now lands past the foreign pages.
        val poolBefore = stats().poolBytes
        val heapBaseBefore = stats().heapBase
        val acrossTheGap = BufferFactory.Default.allocate(freeInPool() + SLACK_BYTES)
        assertTrue(stats().poolBytes > poolBefore, "the pool should have grown across the gap")
        // Proves the non-contiguous branch actually ran: re-basing is the only thing that moves
        // heapBase, so without this the test could pass having taken the ordinary contiguous path.
        assertTrue(
            stats().heapBase > heapBaseBefore,
            "expected the allocator to re-base past the foreign gap, but heapBase stayed at $heapBaseBefore",
        )

        // Memory on the far side of the gap must be usable...
        acrossTheGap.writeInt(SENTINEL)
        acrossTheGap.resetForRead()
        assertEquals(SENTINEL, acrossTheGap.readInt(), "memory past a foreign gap must be usable")

        // ...and the pre-gap buffer must be untouched by the re-base.
        beforeGap.resetForRead()
        assertEquals(SENTINEL, beforeGap.readInt(), "re-basing must not disturb blocks already handed out")

        // Releasing either one must not corrupt the allocator. The pre-gap block now sits below
        // heapBase, so it is rejected and leaks — deliberately the safe direction.
        acrossTheGap.freeNativeMemory()
        beforeGap.freeNativeMemory()

        // The allocator must still hand out usable, non-overlapping memory afterwards.
        val a = BufferFactory.Default.allocate(PROBE_SIZE) as LinearBuffer
        val b = BufferFactory.Default.allocate(PROBE_SIZE) as LinearBuffer
        a.writeInt(SENTINEL)
        b.writeInt(OTHER_SENTINEL)
        a.resetForRead()
        b.resetForRead()
        assertEquals(SENTINEL, a.readInt(), "allocator must stay usable after a non-contiguous grow")
        assertEquals(OTHER_SENTINEL, b.readInt(), "blocks handed out after a re-base must not overlap")
        b.freeNativeMemory()
        a.freeNativeMemory()
    }

    private companion object {
        private const val MIB = 1024 * 1024
        private const val PAGE_SIZE = 65536
        private const val FOREIGN_PAGES = 4
        private const val PROBE_SIZE = 4096
        private const val SLACK_BYTES = 64 * 1024
        private const val SENTINEL = 0x0BADF00D
        private const val OTHER_SENTINEL = 0x0C0FFEE0
    }
}
