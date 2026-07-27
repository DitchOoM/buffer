@file:OptIn(UnsafeWasmMemoryApi::class, ExperimentalWasmJsInterop::class)

package com.ditchoom.buffer

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi

private const val PAGE_SIZE = 65536 // 64KB per WASM page

/** Mask for rounding a size up to the next 8-byte boundary (`Long.SIZE_BYTES - 1`). */
private const val ALIGN_8_MASK = 7

/**
 * Allocation statistics for debugging and monitoring.
 *
 * [totalAllocated] is the bump watermark (`nextOffset - heapBase`), i.e. the high-water mark of linear
 * memory handed out and not yet rewound. [freeListBytes] is the portion of that watermark currently
 * parked on the free list and available for reuse, so live bytes are `totalAllocated - freeListBytes`.
 */
data class AllocationStats(
    val heapBase: Int,
    val nextOffset: Int,
    val totalAllocated: Int,
    val freeListBytes: Int = 0,
    val reusedBlocks: Int = 0,
)

/**
 * JavaScript interop for memory growth.
 * Uses the WASM module's exported memory object.
 *
 * The @JsFun body is a single JS expression string that cannot be wrapped without breaking interop.
 */
@Suppress("MaxLineLength")
@JsFun("(pages) => { if (typeof wasmExports !== 'undefined' && wasmExports.memory) { return wasmExports.memory.grow(pages); } return -1; }")
private external fun jsMemoryGrow(pages: Int): Int

/** The @JsFun body is a single JS expression string that cannot be wrapped without breaking interop. */
@Suppress("MaxLineLength")
@JsFun("() => { if (typeof wasmExports !== 'undefined' && wasmExports.memory) { return wasmExports.memory.buffer.byteLength; } return 0; }")
private external fun jsMemorySize(): Int

/**
 * Configuration for LinearBuffer memory allocation.
 *
 * Call [LinearMemoryAllocator.configure] before any LinearBuffer allocation
 * to customize the initial memory size.
 *
 * ```kotlin
 * // At app startup, before any buffer allocation:
 * LinearMemoryAllocator.configure(initialSizeMB = 8)
 * ```
 */
object LinearMemoryConfig {
    /**
     * Initial memory allocation in megabytes. Default is 256MB.
     * Must be set before the first LinearBuffer allocation.
     *
     * The larger default ensures sufficient headroom for:
     * - Multiple buffer allocations within scoped regions
     * - Benchmark workloads with many iterations
     * - Applications using Direct allocation zone
     */
    var initialSizeMB: Int = 256
        internal set
}

/**
 * Linear memory allocator for buffer storage.
 *
 * This allocator grows WASM linear memory via JavaScript interop and uses
 * the new pages exclusively for buffer allocations.
 *
 * The allocation strategy:
 * 1. Call memory.grow() to add pages - returns previous size
 * 2. Use the offset (previousSize * 64KB) as our heap base
 * 3. Subsequent allocations reuse an exact-fit block from the free list, or bump from there
 * 4. [free] returns a block: rewinding the bump pointer if it is the top allocation, otherwise
 *    parking it on the size-classed free list
 *
 * Linear memory is not garbage collected. A block is only reclaimed when its owning [LinearBuffer]
 * is explicitly released via `freeNativeMemory()` / `use { }`; dropping the reference leaks it.
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

    /**
     * Size-classed free list, exact fit: a freed 8 KiB block never satisfies a 4 KiB request. That
     * keeps both hot paths to an array index and one i32 load/store, with no splitting or coalescing
     * bookkeeping.
     *
     * The list is *intrusive* — the "next" link lives in the first 4 bytes of the freed block itself
     * (which is dead memory, and always at least 8 bytes since sizes are 8-byte aligned), reached
     * with a raw [Pointer]. So push and pop allocate nothing: no boxed `Int` key, no list node, no
     * hash lookup, nothing for the Wasm-GC collector to trace. That matters because allocation is
     * the hot path this whole object exists to serve: a `HashMap<Int, MutableList<Int>>` version of
     * the same free list measured 9.3M vs 14.8M ops/sec on `WasmAllocatorBenchmark`'s reuse route,
     * because every allocation boxed the size key (`struct.new $kotlin.Int` in the emitted wasm).
     *
     * [smallHeads] holds the head offset per size class (`aligned ushr 3`) up to [SMALL_MAX_SIZE],
     * covering every buffer size worth optimising. Larger blocks are rare and their allocation cost
     * is dominated by their size, so they fall back to [largeHeads], which is the same intrusive
     * chain behind a boxed lookup.
     *
     * Deliberately plain Kotlin integer and pointer code: the Kotlin/WASM optimizer bug documented
     * on [allocateOffset] means neither the allocation nor the deallocation path may contain a
     * `@JsFun` call. [Pointer] compiles to native i32.load/i32.store, so it does not.
     */
    private val smallHeads = IntArray(SMALL_CLASS_COUNT) { NO_BLOCK }
    private val largeHeads = HashMap<Int, Int>()
    private var freeListBytes: Int = 0
    private var reusedBlocks: Int = 0

    /**
     * Head of the free chain for [aligned], or [NO_BLOCK].
     *
     * The small path compiles to a compare, a shift and an array load. The large path has to box the
     * key for the map lookup (`struct.new $kotlin.Int` in the emitted wasm), so it is guarded on
     * [largeHeads] being non-empty — a caller that never frees a >= 64 KiB block never pays for it.
     */
    private fun headOf(aligned: Int): Int =
        when {
            aligned < SMALL_MAX_SIZE -> smallHeads[aligned ushr ALIGN_8_SHIFT]
            largeHeads.isEmpty() -> NO_BLOCK
            else -> largeHeads[aligned] ?: NO_BLOCK
        }

    private fun setHead(
        aligned: Int,
        offset: Int,
    ) {
        if (aligned < SMALL_MAX_SIZE) {
            smallHeads[aligned ushr ALIGN_8_SHIFT] = offset
        } else if (offset == NO_BLOCK) {
            // Drop the drained chain rather than parking a -1, so headOf's isEmpty() guard stays live.
            largeHeads.remove(aligned)
        } else {
            largeHeads[aligned] = offset
        }
    }

    // Computed from config - 1MB = 16 pages (16 * 64KB)
    private val initialPages: Int get() = LinearMemoryConfig.initialSizeMB * 16

    /**
     * Configure the initial memory allocation size.
     * Must be called before any LinearBuffer allocation.
     *
     * @param initialSizeMB Initial memory size in megabytes (default: 16MB)
     * @throws IllegalStateException if called after memory has been initialized
     */
    fun configure(initialSizeMB: Int = 16) {
        if (initialized) {
            throw IllegalStateException(
                "LinearMemoryAllocator already initialized. " +
                    "Call configure() before any LinearBuffer allocation.",
            )
        }
        require(initialSizeMB > 0) { "initialSizeMB must be positive" }
        LinearMemoryConfig.initialSizeMB = initialSizeMB
    }

    /**
     * Initialize the allocator by growing memory.
     * Called automatically on first allocation.
     */
    private fun ensureInitialized() {
        if (initialized) return

        // Grow memory to get pages exclusively for our use
        val previousSizePages = jsMemoryGrow(initialPages)
        if (previousSizePages == -1) {
            throw OutOfMemoryError("Failed to grow WASM memory for buffer allocation")
        }

        // Our heap starts at the old memory boundary
        heapBase = previousSizePages * PAGE_SIZE
        nextOffset = heapBase
        heapEnd = heapBase + (initialPages * PAGE_SIZE)
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
        val aligned = (size + ALIGN_8_MASK) and ALIGN_8_MASK.inv()
        lastAlignedSize = aligned

        // Reuse a previously freed block of exactly this size before growing the watermark.
        // Recycled memory is NOT zeroed — same contract as malloc, and as Linux `NativeBuffer`.
        // Use BufferFactory.secure() (or zeroInit) when the contents must start at zero.
        val recycled = headOf(aligned)
        if (recycled != NO_BLOCK) {
            // The block's first 4 bytes hold the next link; reading it releases the block entirely.
            setHead(aligned, Pointer(recycled.toUInt()).loadInt())
            freeListBytes -= aligned
            reusedBlocks++
            return recycled
        }

        // Check bounds but don't try to grow (would require @JsFun call)
        if (nextOffset + aligned > heapEnd) {
            throw OutOfMemoryError(
                "LinearBuffer allocation exceeded ${LinearMemoryConfig.initialSizeMB}MB pre-allocated memory. " +
                    "Use BufferFactory.managed() for high-frequency allocations, " +
                    "or call LinearMemoryAllocator.configure(initialSizeMB = N) at startup with a larger value.",
            )
        }

        val offset = nextOffset
        nextOffset += aligned
        return offset
    }

    /**
     * Return a block previously handed out by [allocate] / [allocateOffset] so it can satisfy a later
     * request of the same size.
     *
     * Called by [LinearBuffer.freeNativeMemory], which guards against double-free and against freeing
     * a non-owning view (a `slice()`), so this function trusts its arguments beyond the cheap sanity
     * bounds check below.
     *
     * Two reclamation strategies, both pure integer math:
     * - If the block sits at the top of the heap, rewind the bump pointer. This is what an
     *   allocate/use/free loop does, so the steady-state watermark stays flat.
     * - Otherwise park the block on the size-classed free list for an exact-fit reuse.
     *
     * @param offset the block's base offset, as returned by [allocateOffset]
     * @param size the block's requested (unaligned) size — the same value passed to [allocate]
     */
    fun free(
        offset: Int,
        size: Int,
    ) {
        if (!initialized) return
        val aligned = (size + ALIGN_8_MASK) and ALIGN_8_MASK.inv()
        if (aligned <= 0) return
        // Reject anything that was never handed out by this allocator (e.g. a wrapped external
        // address). Blocks are always fully below the watermark at the time they are freed.
        if (offset < heapBase || offset + aligned > nextOffset) return

        if (offset + aligned == nextOffset) {
            // Top of heap: give the bytes back outright.
            //
            // This can never strand a free-list entry above the new watermark. Allocations do not
            // overlap, so any parked block X satisfies either X + sizeX <= offset (stays below the
            // rewound watermark) or X >= nextOffset (impossible — X was handed out below it).
            nextOffset = offset
            return
        }

        // Push onto the intrusive chain: the old head goes into the block's first 4 bytes.
        Pointer(offset.toUInt()).storeInt(headOf(aligned))
        setHead(aligned, offset)
        freeListBytes += aligned
    }

    // Helper for test functions that need initialization
    private fun initializeMemory() {
        val previousSizePages = jsMemoryGrow(initialPages)
        if (previousSizePages == -1) {
            throw OutOfMemoryError("Failed to grow WASM memory for buffer allocation")
        }
        heapBase = previousSizePages * PAGE_SIZE
        nextOffset = heapBase
        heapEnd = heapBase + (initialPages * PAGE_SIZE)
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
        val aligned = (size + ALIGN_8_MASK) and ALIGN_8_MASK.inv()
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
    fun testAlignmentOnly(size: Int): Int = (size + ALIGN_8_MASK) and ALIGN_8_MASK.inv()

    /**
     * Test: Alignment + assignment to lastAlignedSize
     */
    fun testAlignmentWithAssignment(size: Int): Int {
        val aligned = (size + ALIGN_8_MASK) and ALIGN_8_MASK.inv()
        lastAlignedSize = aligned
        return aligned
    }

    /**
     * Zero-initialize a memory region using JS Uint8Array.fill(0) — single native call.
     */
    private fun zeroMemory(
        offset: Int,
        size: Int,
    ) {
        UnsafeMemory.setMemory(offset.toLong(), size.toLong(), 0)
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
            freeListBytes = freeListBytes,
            reusedBlocks = reusedBlocks,
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
        smallHeads.fill(NO_BLOCK)
        largeHeads.clear()
        freeListBytes = 0
        reusedBlocks = 0
    }

    private const val NO_BLOCK = -1

    /** `log2(8)` — turns an 8-byte-aligned size into its [smallHeads] index. */
    private const val ALIGN_8_SHIFT = 3

    /** Free-list size classes are tracked in an array up to this size; above it, in a hash map. */
    private const val SMALL_MAX_SIZE = 64 * 1024

    private const val SMALL_CLASS_COUNT = SMALL_MAX_SIZE ushr ALIGN_8_SHIFT
}
