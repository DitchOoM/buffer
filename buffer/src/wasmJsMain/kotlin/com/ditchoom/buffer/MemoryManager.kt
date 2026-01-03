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

    // Allocate 1MB initially, grow by 1MB increments
    private const val INITIAL_PAGES = 16
    private const val GROWTH_PAGES = 16

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

    /**
     * Allocate memory for a buffer.
     *
     * @param size Requested size in bytes
     * @return Pair of (offset, alignedCapacity) where offset can be used with Pointer
     */
    fun allocate(size: Int): Pair<Int, Int> {
        ensureInitialized()

        // 8-byte alignment for optimal memory access
        val aligned = (size + 7) and 7.inv()

        // Grow memory if needed
        if (nextOffset + aligned > heapEnd) {
            val neededBytes = (nextOffset + aligned) - heapEnd
            val neededPages = (neededBytes + PAGE_SIZE - 1) / PAGE_SIZE
            val pagesToGrow = maxOf(neededPages, GROWTH_PAGES)

            val result = jsMemoryGrow(pagesToGrow)
            if (result == -1) {
                throw OutOfMemoryError("Failed to grow WASM memory: needed $pagesToGrow pages")
            }
            heapEnd += pagesToGrow * PAGE_SIZE
        }

        val offset = nextOffset
        nextOffset += aligned

        // Zero-initialize the memory (new pages are zeroed by WASM spec,
        // but reused space after reset() might have old data)
        zeroMemory(offset, aligned)

        return Pair(offset, aligned)
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
