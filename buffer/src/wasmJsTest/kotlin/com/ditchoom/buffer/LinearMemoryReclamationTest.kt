package com.ditchoom.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Mechanism-level regression gate for wasmJs linear-memory reclamation, read straight off the
 * allocator's own counters rather than inferred from an OutOfMemoryError.
 *
 * These assertions used to run the other way round: they pinned the broken behaviour, where
 * [LinearMemoryAllocator] was a pure bump allocator with no deallocation entry point and
 * [LinearBuffer] inherited the empty `freeNativeMemory` default, so releasing a buffer reclaimed
 * nothing and `deterministic().use { }` leaked. They now assert that releasing a buffer gives its
 * block back.
 *
 * Every test uses its own [SIZE_CLASS] value. Reuse is exact-fit, and wasm tests share one process
 * with one global allocator, so distinct sizes keep the tests independent of execution order.
 */
class LinearMemoryReclamationTest {
    private fun watermark(): Int = LinearMemoryAllocator.getAllocationStats().totalAllocated

    private fun freeListBytes(): Int = LinearMemoryAllocator.getAllocationStats().freeListBytes

    private fun reusedBlocks(): Int = LinearMemoryAllocator.getAllocationStats().reusedBlocks

    @Test
    fun allocationAdvancesTheWatermark() {
        val size = sizeClass(0)
        val before = watermark()
        BufferFactory.Default.allocate(size)
        assertTrue(watermark() >= before + size, "expected the bump pointer to advance by at least $size")
    }

    /**
     * The core defect, inverted. `freeNativeMemory()` is the documented way to hand native memory
     * back; on a LinearBuffer it used to change nothing.
     */
    @Test
    fun freeNativeMemoryReclaimsTheBlock() {
        val before = watermark()
        val buffer = BufferFactory.Default.allocate(sizeClass(1))
        assertTrue(watermark() > before, "allocation should have consumed linear memory")
        buffer.freeNativeMemory()
        assertEquals(before, watermark(), "freeNativeMemory() must return the block to the allocator")
    }

    /** Same for the scope-exit form, which is what `use { }` calls underneath. */
    @Test
    fun deterministicUseBlockReclaimsImmediately() {
        val before = watermark()
        BufferFactory.deterministic().allocate(sizeClass(2)).use { it.writeByte(1) }
        assertEquals(
            before,
            watermark(),
            "deterministic().use { } is documented to free immediately, with no GC involved",
        )
    }

    /** An allocate/use/release loop must reach a flat steady state, not a rising watermark. */
    @Test
    fun repeatedAllocateAndReleaseDoesNotGrowTheWatermark() {
        val size = sizeClass(3)
        BufferFactory.Default.allocate(size).use { it.writeByte(1) }
        val afterFirst = watermark()
        repeat(ITERATIONS) { BufferFactory.Default.allocate(size).use { buffer -> buffer.writeByte(1) } }
        assertEquals(afterFirst, watermark(), "$ITERATIONS allocate/release cycles must not accumulate")
    }

    /**
     * A block freed from the middle of the heap (not the top, so the bump pointer cannot simply
     * rewind) is parked on the size-classed free list and handed back out to the next request of the
     * same size — exactly once.
     */
    @Test
    fun freedBlockIsHandedBackOutExactlyOnce() {
        val size = sizeClass(4)
        val first = BufferFactory.Default.allocate(size) as LinearBuffer
        val pinnedAbove = BufferFactory.Default.allocate(size) as LinearBuffer

        val reusedBefore = reusedBlocks()
        first.freeNativeMemory()
        assertTrue(freeListBytes() > 0, "a non-top-of-heap block should be parked on the free list")

        val recycled = BufferFactory.Default.allocate(size) as LinearBuffer
        assertEquals(first.baseOffset, recycled.baseOffset, "the freed block should satisfy the next request")
        assertEquals(reusedBefore + 1, reusedBlocks(), "reuse should be counted")

        // The block must not still be on the free list after being handed out.
        val fresh = BufferFactory.Default.allocate(size) as LinearBuffer
        assertNotEquals(first.baseOffset, fresh.baseOffset, "a freed block must not be issued twice")
        assertNotEquals(pinnedAbove.baseOffset, fresh.baseOffset, "a live block must not be reissued")
    }

    /** Releasing twice must reclaim once — a double free would corrupt the free list. */
    @Test
    fun doubleFreeReclaimsOnce() {
        val size = sizeClass(5)
        val buffer = BufferFactory.Default.allocate(size)
        BufferFactory.Default.allocate(size) // pins `buffer` below the top of the heap

        buffer.freeNativeMemory()
        val afterFirstFree = freeListBytes()
        buffer.freeNativeMemory()
        assertEquals(afterFirstFree, freeListBytes(), "the second release must be a no-op")

        val recycled = BufferFactory.Default.allocate(size) as LinearBuffer
        val fresh = BufferFactory.Default.allocate(size) as LinearBuffer
        assertNotEquals(recycled.baseOffset, fresh.baseOffset, "the block must not be on the free list twice")
    }

    @Test
    fun isFreedTracksTheRelease() {
        val buffer = BufferFactory.Default.allocate(sizeClass(6))
        val closeable = buffer as CloseableBuffer
        assertFalse(closeable.isFreed, "a fresh buffer is not freed")
        buffer.freeNativeMemory()
        assertTrue(closeable.isFreed, "isFreed must reflect the release")
    }

    // ============================================================================
    // Slice lifetime — slices alias the parent's memory and must never free it
    // ============================================================================

    /**
     * The main hazard the free list introduces. A slice shares the parent's `baseOffset`; if it were
     * treated as an owner, releasing it would hand a live block back to the allocator, which would
     * then reissue memory the parent is still writing to.
     */
    @Test
    fun releasingASliceDoesNotReclaimTheParentBlock() {
        val size = sizeClass(7)
        val parent = BufferFactory.Default.allocate(size)
        val afterAllocate = watermark()
        val freeListBefore = freeListBytes()

        val slice = parent.slice()
        slice.freeNativeMemory()

        assertEquals(afterAllocate, watermark(), "a slice must not return its parent's block")
        assertEquals(freeListBefore, freeListBytes(), "a slice must not reach the free list")
        assertFalse((slice as CloseableBuffer).isFreed, "a non-owning view is never 'freed'")

        // The parent is still usable, and still the owner.
        parent.writeByte(7)
        parent.freeNativeMemory()
        assertEquals(afterAllocate - alignUp(size), watermark(), "the parent still owns the block")
    }

    /** A slice stays a valid view of the parent for as long as the parent is alive. */
    @Test
    fun sliceReadsThroughToTheParentWhileTheParentIsAlive() {
        val parent = BufferFactory.Default.allocate(sizeClass(8))
        parent.writeInt(0x0BADF00D)
        parent.resetForRead()

        val slice = parent.slice()
        slice.use { assertEquals(0x0BADF00D, it.readInt()) }

        parent.position(0)
        assertEquals(0x0BADF00D, parent.readInt(), "using the slice must not disturb the parent")
        parent.freeNativeMemory()
    }

    /**
     * `wrapNativeAddress` hands back a view of memory the caller owns. Releasing it must not push
     * somebody else's address onto the free list.
     */
    @Test
    fun wrappedExternalAddressIsNeverReclaimed() {
        val size = sizeClass(9)
        val owner = BufferFactory.Default.allocate(size) as LinearBuffer
        val afterAllocate = watermark()
        val freeListBefore = freeListBytes()

        val wrapped = PlatformBuffer.wrapNativeAddress(owner.nativeAddress, size)
        wrapped.freeNativeMemory()

        assertEquals(afterAllocate, watermark(), "wrapping must not take ownership")
        assertEquals(freeListBefore, freeListBytes(), "an externally-owned address must not be parked")
        owner.freeNativeMemory()
    }

    /**
     * Blocks at or above the free list's array-backed size classes take the map-backed path. Same
     * contract, different bookkeeping, so it gets its own round trip.
     */
    @Test
    fun largeBlocksAreReclaimedAndReused() {
        val size = LARGE_SIZE
        val first = BufferFactory.Default.allocate(size) as LinearBuffer
        BufferFactory.Default.allocate(size) // pins `first` below the top of the heap

        first.freeNativeMemory()
        val recycled = BufferFactory.Default.allocate(size) as LinearBuffer
        assertEquals(first.baseOffset, recycled.baseOffset, "a large freed block should be reused")

        val fresh = BufferFactory.Default.allocate(size) as LinearBuffer
        assertNotEquals(first.baseOffset, fresh.baseOffset, "a large block must not be issued twice")
    }

    // ============================================================================
    // Allocator boundaries
    // ============================================================================

    /** The escape hatch: a managed buffer never touches linear memory, so the watermark is untouched. */
    @Test
    fun managedFactoryDoesNotConsumeLinearMemory() {
        val before = watermark()
        repeat(REPEATS) { BufferFactory.managed().allocate(sizeClass(10)).writeByte(1) }
        assertEquals(before, watermark(), "managed() must not draw from linear memory")
    }

    /**
     * WASM has no GC-backed alternative for linear memory, so `deterministic()` draws from the same
     * allocator as `Default` and costs the same. What it documents — release is immediate and does
     * not wait on a collector — is now true of both.
     */
    @Test
    fun deterministicMatchesDefaultAndBothAreCloseable() {
        val size = sizeClass(11)
        val before = watermark()
        val deterministic = BufferFactory.deterministic().allocate(size)
        val deterministicCost = watermark() - before

        val beforeDefault = watermark()
        val default = BufferFactory.Default.allocate(size)
        val defaultCost = watermark() - beforeDefault

        assertEquals(defaultCost, deterministicCost, "deterministic() draws from the same allocator as Default")
        assertTrue(deterministic is CloseableBuffer && default is CloseableBuffer)
        default.freeNativeMemory()
        deterministic.freeNativeMemory()
        assertEquals(before, watermark(), "both must reclaim on release")
    }

    /** Reuse via a pool: the pool holds one buffer and hands it back out without new linear memory. */
    @Test
    fun poolingReusesInsteadOfReclaiming() {
        val size = sizeClass(12)
        val pool =
            com.ditchoom.buffer.pool
                .BufferPool(defaultBufferSize = size)
        val factory = BufferFactory.Default.withPooling(pool)
        repeat(WARMUP) { factory.allocate(size).also { it.writeByte(1) }.freeNativeMemory() }
        val afterWarm = watermark()
        repeat(REPEATS) { factory.allocate(size).also { it.writeByte(1) }.freeNativeMemory() }
        assertEquals(
            afterWarm,
            watermark(),
            "a warmed pool must satisfy further requests without new linear memory",
        )
    }

    private fun alignUp(size: Int): Int = (size + ALIGN - 1) and (ALIGN - 1).inv()

    /** A distinct exact-fit size class per test, so tests cannot recycle each other's blocks. */
    private fun sizeClass(index: Int): Int = BASE_SIZE + index * ALIGN

    private companion object {
        private const val BASE_SIZE = 4096
        private const val ALIGN = 8

        /** Above the free list's array-backed size classes, so it exercises the map-backed path. */
        private const val LARGE_SIZE = 128 * 1024
        private const val ITERATIONS = 1000
        private const val REPEATS = 64
        private const val WARMUP = 8
    }
}
