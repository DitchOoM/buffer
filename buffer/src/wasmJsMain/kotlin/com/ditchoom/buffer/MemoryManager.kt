@file:OptIn(UnsafeWasmMemoryApi::class, ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi

private const val PAGE_SIZE = 65536 // 64KB per WASM page

/**
 * Allocation statistics for debugging and monitoring.
 */
data class AllocationStats(
    val heapBase: Int,
    val nextOffset: Int,
    val totalAllocated: Int,
)

/**
 * JavaScript interop for memory growth.
 * Uses the WASM module's exported memory object.
 */
@JsFun("(pages) => { if (typeof wasmExports !== 'undefined' && wasmExports.memory) { return wasmExports.memory.grow(pages); } return -1; }")
private external fun jsMemoryGrow(pages: Int): Int

@JsFun("() => { if (typeof wasmExports !== 'undefined' && wasmExports.memory) { return wasmExports.memory.buffer.byteLength; } return 0; }")
private external fun jsMemorySize(): Int

/**
 * Linear memory allocator for buffer storage.
 *
 * This allocator grows WASM linear memory via JavaScript interop and uses
 * the new pages exclusively for buffer allocations.
 *
 * The allocation strategy:
 * 1. Call memory.grow() to add pages - returns previous size
 * 2. Use the offset (previousSize * 64KB) as our heap base
 * 3. All subsequent allocations bump from there
 *
 * This is safe because:
 * - memory.grow() returns pages that weren't previously mapped
 * - Kotlin/WASM uses WasmGC for objects (separate from linear memory)
 * - Pointer operations compile to native i32.load/i32.store
 */
object LinearMemoryAllocator {
    private var initialized = false
    private var heapBase: Int = 0
    private var nextOffset: Int = 0
    private var heapEnd: Int = 0

    // Allocate 256MB initially (4096 pages * 64KB)
    // This large allocation works around the optimizer bug that prevents
    // calling jsMemoryGrow during allocation
    private const val INITIAL_PAGES = 4096
    private const val GROWTH_PAGES = 4096

    /**
     * Initialize the allocator by growing memory.
     * Called automatically on first allocation.
     */
    private fun ensureInitialized() {
        if (initialized) return

        // Grow memory to get pages exclusively for our use
        val previousSizePages = jsMemoryGrow(INITIAL_PAGES)
        if (previousSizePages == -1) {
            throw OutOfMemoryError("Failed to grow WASM memory for buffer allocation")
        }

        // Our heap starts at the old memory boundary
        heapBase = previousSizePages * PAGE_SIZE
        nextOffset = heapBase
        heapEnd = heapBase + (INITIAL_PAGES * PAGE_SIZE)
        initialized = true
    }

    // Store the last aligned size for callers that need it
    var lastAlignedSize: Int = 0
        private set

    /**
     * Allocate memory for a buffer.
     *
     * @param size Requested size in bytes
     * @return Pair of (offset, alignedCapacity) where offset can be used with Pointer
     */
    fun allocate(size: Int): Pair<Int, Int> {
        val offset = allocateOffset(size)
        return Pair(offset, lastAlignedSize)
    }

    /**
     * Allocate memory and return just the offset.
     * The aligned size is stored in [lastAlignedSize].
     *
     * WORKAROUND: Due to a Kotlin/WASM optimizer bug, having @JsFun calls
     * (like jsMemoryGrow) anywhere in the function body causes stack overflow
     * in production builds. We work around this by:
     * 1. Pre-allocating enough memory at init time
     * 2. Not calling jsMemoryGrow during normal allocation
     * 3. Throwing if we run out (shouldn't happen with large initial alloc)
     */
    fun allocateOffset(size: Int): Int {
        // Use initializeMemory which is called once
        if (!initialized) {
            initializeMemory()
        }

        // 8-byte alignment for optimal memory access
        val aligned = (size + 7) and 7.inv()
        lastAlignedSize = aligned

        // Check bounds but don't try to grow (would require @JsFun call)
        if (nextOffset + aligned > heapEnd) {
            throw OutOfMemoryError(
                "LinearBuffer allocation exceeded pre-allocated memory. " +
                    "Increase INITIAL_PAGES or use AllocationZone.Heap for high-frequency allocation.",
            )
        }

        val offset = nextOffset
        nextOffset += aligned
        return offset
    }

    // Helper for test functions that need initialization
    private fun initializeMemory() {
        val previousSizePages = jsMemoryGrow(INITIAL_PAGES)
        if (previousSizePages == -1) {
            throw OutOfMemoryError("Failed to grow WASM memory for buffer allocation")
        }
        heapBase = previousSizePages * PAGE_SIZE
        nextOffset = heapBase
        heapEnd = heapBase + (INITIAL_PAGES * PAGE_SIZE)
        initialized = true
    }

    /**
     * Minimal allocator that just returns an incrementing offset.
     * For debugging the optimizer bug - no @JsFun calls after init.
     */
    fun allocateMinimal(size: Int): Int {
        if (!initialized) {
            initializeMemory()
        }
        val offset = nextOffset
        nextOffset += size
        return offset
    }

    /**
     * Test: minimal + alignment only
     */
    fun allocateWithAlignment(size: Int): Int {
        if (!initialized) {
            initializeMemory()
        }
        val aligned = (size + 7) and 7.inv()
        val offset = nextOffset
        nextOffset += aligned
        return offset
    }

    /**
     * Test: minimal + bounds check only (no alignment, no grow call)
     */
    fun allocateWithBoundsCheck(size: Int): Int {
        if (!initialized) {
            initializeMemory()
        }
        // Just the comparison, don't actually grow (we have 1MB)
        if (nextOffset + size > heapEnd) {
            return -1 // Signal overflow without calling growMemory
        }
        val offset = nextOffset
        nextOffset += size
        return offset
    }

    /**
     * Test: Just the alignment math, nothing else
     */
    fun testAlignmentOnly(size: Int): Int = (size + 7) and 7.inv()

    /**
     * Test: Alignment + assignment to lastAlignedSize
     */
    fun testAlignmentWithAssignment(size: Int): Int {
        val aligned = (size + 7) and 7.inv()
        lastAlignedSize = aligned
        return aligned
    }

    /**
     * Zero-initialize a memory region.
     */
    private fun zeroMemory(
        offset: Int,
        size: Int,
    ) {
        var i = 0
        // Zero 8 bytes at a time for efficiency
        while (i + 8 <= size) {
            Pointer((offset + i).toUInt()).storeLong(0L)
            i += 8
        }
        // Handle remaining bytes
        while (i < size) {
            Pointer((offset + i).toUInt()).storeByte(0)
            i++
        }
    }

    /**
     * Get current allocation statistics.
     */
    fun getAllocationStats(): AllocationStats {
        ensureInitialized()
        return AllocationStats(
            heapBase = heapBase,
            nextOffset = nextOffset,
            totalAllocated = nextOffset - heapBase,
        )
    }

    /**
     * Reset the allocator (for testing only).
     * WARNING: This invalidates all previously allocated buffers!
     */
    fun reset() {
        if (initialized) {
            // Zero out previously used memory
            val usedBytes = nextOffset - heapBase
            if (usedBytes > 0) {
                zeroMemory(heapBase, usedBytes)
            }
            nextOffset = heapBase
        }
    }
}
