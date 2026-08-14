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
 * [poolBytes] is how much linear memory the allocator has claimed in total — the initial reservation
 * plus every growth step — so it rises when the pool has had to grow.
 */
data class AllocationStats(
    val heapBase: Int,
    val nextOffset: Int,
    val totalAllocated: Int,
    val freeListBytes: Int = 0,
    val reusedBlocks: Int = 0,
    val poolBytes: Int = 0,
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
 * Call [LinearMemoryAllocator.configure] before any LinearBuffer allocation to set how much linear
 * memory is reserved up front and how far the pool may grow.
 *
 * ```kotlin
 * // At app startup, before any buffer allocation:
 * LinearMemoryAllocator.configure(initialSizeMB = 8, maxSizeMB = 128)
 * ```
 */
object LinearMemoryConfig {
    /**
     * Linear memory reserved up front, in megabytes. Must be set before the first LinearBuffer
     * allocation.
     *
     * This used to default to 256MB, because the pool could not grow and the initial reservation was
     * also the hard ceiling. The pool now grows on demand, so this is just a starting point: it only
     * needs to cover the steady state of a typical workload, and anything beyond it is picked up in
     * 16MB steps.
     */
    var initialSizeMB: Int = 16
        internal set

    /**
     * Upper bound on total linear memory the allocator will claim, in megabytes.
     *
     * Growth is what keeps a legitimate workload from hitting an arbitrary cliff, but linear memory
     * is never returned to the engine, so an unreleased-buffer leak would otherwise climb until the
     * engine itself refuses — taking the page down instead of reporting a bug. This bound keeps that
     * failure diagnosable, and defaults to the ceiling the fixed 256MB pool used to impose, so no
     * workload that fit before fails now. Raise it explicitly if you genuinely need more.
     */
    var maxSizeMB: Int = 256
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
 * 2. Base the pool above both that previous size and [RUNTIME_SCRATCH_RESERVE_BYTES]
 *    (see [runtimeScratchReserveBytes] for why the bottom of linear memory is not ours to take)
 * 3. Subsequent allocations reuse an exact-fit block from the free list, or bump from there
 * 4. [free] returns a block: rewinding the bump pointer if it is the top allocation, otherwise
 *    parking it on the size-classed free list
 * 5. Running out grows the pool again ([growMemory]); allocation only fails once the engine refuses
 *
 * Linear memory is not garbage collected. A block is only reclaimed when its owning [LinearBuffer]
 * is explicitly released via `freeNativeMemory()` / `use { }`; dropping the reference leaks it.
 *
 * This is safe because:
 * - the pool sits above the region the Kotlin/Wasm runtime allocates its own scratch from
 * - Kotlin/WASM uses WasmGC for objects (separate from linear memory)
 * - Pointer operations compile to native i32.load/i32.store
 */
object LinearMemoryAllocator {
    private var initialized = false
    private var heapBase: Int = 0
    private var nextOffset: Int = 0
    private var heapEnd: Int = 0

    /**
     * Bytes at the bottom of linear memory the pool must never claim, because the Kotlin/Wasm
     * runtime allocates its own scratch there.
     *
     * `memory.grow()` returning the previous page count reads like a watermark above which the
     * new address space is exclusively ours. It is not. The stdlib's own allocator — the one
     * behind `kotlin.wasm.unsafe.withScopedMemoryAllocator`, which every piece of interop that
     * exchanges bytes with the host goes through — opens each top-level scope as
     * `ScopedMemoryAllocator(startAddress = 0)`. It bump-allocates upward **from address 0** every
     * time, and grows memory only when its bump pointer would run past the current size. It never
     * consults, and cannot be told about, memory somebody else grew.
     *
     * So the low end of linear memory is the runtime's, permanently and repeatedly. A pool based
     * there is handed out on top of live interop scratch, in both directions: the runtime's writes
     * corrupt buffer contents, and — since the free list's next-link lives in the first four bytes
     * of a parked block — they corrupt the free list itself into wild offsets that trap the next
     * allocation of that size class as `memory access out of bounds`.
     *
     * That is not a theoretical ordering hazard. A Kotlin/Wasm module can start with **zero** pages
     * of linear memory (it is WasmGC; nothing but the unsafe API puts anything there), in which case
     * `memory.grow` returns 0 on the first allocation and the pool is based at address 0 — colliding
     * with *every* subsequent scoped allocation.
     *
     * The reserve is address space only: WASM commits pages on first touch, and these are pages the
     * runtime touches anyway. It is not a hard guarantee — a single scoped allocation larger than
     * the reserve would still reach the pool — but it puts the floor far above anything interop
     * scratch does, and [allocateOffset] now reports a breach as a diagnosable error instead of a
     * trap.
     */
    val runtimeScratchReserveBytes: Int get() = RUNTIME_SCRATCH_RESERVE_BYTES

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
     * [smallHeads] holds the head offset per size class (`aligned ushr 3`) up to [SMALL_MAX_SIZE].
     * Larger blocks fall back to [largeHeads] — the same intrusive chain, but behind a boxed map
     * lookup, which `WasmAllocatorBenchmark` measures at 10.1M vs 15.5M ops/sec on the reuse route.
     * Note that allocation is O(1) in the block size (it never touches the memory), so that gap is
     * not amortised by the larger allocation: a workload churning >= 64 KiB buffers pays it in full.
     * Closing it needs a primitive int-keyed map — either `androidx.collection.MutableIntIntMap`
     * (a dependency :buffer-1brc is deliberately kept separate to avoid) or a second intrusive
     * chain storing each block's size in its own header.
     *
     * Deliberately plain Kotlin integer and pointer code: the Kotlin/WASM optimizer bug documented
     * on [allocateOffset] means neither the allocation nor the deallocation path may contain a
     * `@JsFun` call. [Pointer] compiles to native i32.load/i32.store, so it does not.
     */
    private val smallHeads = IntArray(SMALL_CLASS_COUNT) { NO_BLOCK }
    private val largeHeads = HashMap<Int, Int>()
    private var freeListBytes: Int = 0
    private var reusedBlocks: Int = 0

    /** Bytes taken from linear memory across the initial reservation and every [growMemory] step. */
    private var poolBytes: Int = 0

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
    private val initialPages: Int get() = LinearMemoryConfig.initialSizeMB * PAGES_PER_MB

    /**
     * Configure how much linear memory the allocator reserves up front and how far it may grow.
     * Must be called before any LinearBuffer allocation.
     *
     * @param initialSizeMB reserved at first allocation (default: 16MB)
     * @param maxSizeMB ceiling across all growth steps (default: 256MB)
     * @throws IllegalStateException if called after memory has been initialized
     */
    fun configure(
        initialSizeMB: Int = 16,
        maxSizeMB: Int = 256,
    ) {
        if (initialized) {
            throw IllegalStateException(
                "LinearMemoryAllocator already initialized. " +
                    "Call configure() before any LinearBuffer allocation.",
            )
        }
        require(initialSizeMB > 0) { "initialSizeMB must be positive" }
        require(maxSizeMB >= initialSizeMB) {
            "maxSizeMB ($maxSizeMB) must be at least initialSizeMB ($initialSizeMB)"
        }
        LinearMemoryConfig.initialSizeMB = initialSizeMB
        LinearMemoryConfig.maxSizeMB = maxSizeMB
    }

    /**
     * Initialize the allocator by growing memory.
     * Called automatically on first allocation.
     */
    private fun ensureInitialized() {
        if (initialized) return
        initializeMemory()
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
     * WORKAROUND: a Kotlin/WASM optimizer bug makes a `@JsFun` call appearing **directly in this
     * function's body** blow the stack in production builds. It does not extend to a call behind a
     * cold branch in a separate function — [initializeMemory] has always been reached from here and
     * calls `jsMemoryGrow`. [growMemory] relies on that same shape, so running out of pre-allocated
     * memory grows it instead of throwing; the throw is now reserved for the engine refusing.
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
            // Four integer compares standing between a corrupted free list and an unlabelled
            // `memory access out of bounds` trap. Both reads below dereference an offset that only
            // the allocator's own bookkeeping vouches for: `recycled` came from a head slot, and
            // `link` came out of the freed block's own first four bytes, which is *linear memory*
            // and therefore writable by anything holding a stale address. A wild value here traps
            // inside `Pointer.loadInt`, naming nothing; a throw names the offset and the state.
            //
            // The upper bound is `> nextOffset - aligned`, never `+ aligned > nextOffset`: the
            // corrupt values this guard exists to catch are arbitrary, so the addition form wraps
            // for anything near Int.MAX_VALUE and lets exactly those through to the trap. Both
            // operands here are pool offsets bounded by the 256MB ceiling, so the subtraction
            // cannot underflow into a false positive.
            if (recycled < heapBase || recycled > nextOffset - aligned) {
                failCorruptFreeList("head", recycled, aligned, NO_BLOCK)
            }
            // The block's first 4 bytes hold the next link; reading it releases the block entirely.
            val link = Pointer(recycled.toUInt()).loadInt()
            if (link != NO_BLOCK && (link < heapBase || link > nextOffset - aligned)) {
                failCorruptFreeList("link", link, aligned, recycled)
            }
            setHead(aligned, link)
            freeListBytes -= aligned
            reusedBlocks++
            return recycled
        }

        // Out of pre-allocated room: ask the engine for more before giving up.
        if (heapEnd - nextOffset < aligned && !growMemory(aligned)) {
            throw OutOfMemoryError(
                "LinearBuffer allocation of $size bytes failed: the linear-memory pool is at " +
                    "${poolBytes / BYTES_PER_MB}MB of the ${LinearMemoryConfig.maxSizeMB}MB limit. " +
                    "Release LinearBuffers with use { } / freeNativeMemory() — linear memory is not " +
                    "garbage collected, so an unreleased buffer is leaked for the life of the process. " +
                    "Use BufferFactory.managed() for high-frequency allocations, or raise the limit with " +
                    "PlatformBuffer.configureWasmMemory(initialSizeMB, maxSizeMB) before the first allocation.",
            )
        }

        val offset = nextOffset
        nextOffset += aligned
        return offset
    }

    /**
     * Drop the corrupt size class, then report it. Its own function so neither the message building
     * nor the chain surgery lands in [allocateOffset]'s body — only the compares are inlined there,
     * which is also why [what] and [inBlock] arrive as plain arguments rather than as an
     * interpolated string built at the call site.
     *
     * The chain is dropped *before* throwing because the head slot is what the next allocation of
     * this size class reads. Leaving a corrupt head installed turns one report into a permanent one:
     * every subsequent allocation of that class re-reads the same bad offset and throws again, for
     * the life of the process, which is a wedged allocator rather than a reported incident.
     *
     * Dropping it leaks the chain. The links are precisely what is untrustworthy here, so the chain
     * cannot be walked to count or re-park its blocks; those bytes stay counted in [freeListBytes]
     * and are never handed out again. That is the honest post-corruption state — the alternative is
     * following the same links this function exists to reject — and it is bounded by the pool
     * ceiling, unlike the wedge.
     */
    private fun failCorruptFreeList(
        what: String,
        offset: Int,
        aligned: Int,
        inBlock: Int,
    ): Nothing {
        setHead(aligned, NO_BLOCK)
        val where = if (inBlock == NO_BLOCK) what else "$what out of block $inBlock"
        throw IllegalStateException(
            "LinearMemoryAllocator free list is corrupt: $where = $offset " +
                "(0x${offset.toUInt().toString(HEX_RADIX)}) is outside the $aligned-byte size " +
                "class's pool [$heapBase, $nextOffset). Something wrote into a block after it was " +
                "released — the free list's next-link lives in the first four bytes of a released " +
                "block, so a stale write lands on it. Usual causes: a slice() or wrapNativeAddress() " +
                "view used after its owner was released, a retained nativeAddress, or a scoped " +
                "allocation from the Kotlin/Wasm runtime larger than the pool's base offset. " +
                "The $aligned-byte free chain has been dropped so allocation can continue; its " +
                "blocks are leaked for the life of the process. " +
                "Pool: heapBase=$heapBase nextOffset=$nextOffset heapEnd=$heapEnd " +
                "poolBytes=$poolBytes freeListBytes=$freeListBytes reusedBlocks=$reusedBlocks " +
                "runtimeScratchReserveBytes=$RUNTIME_SCRATCH_RESERVE_BYTES",
        )
    }

    /**
     * Grow the pool so it can satisfy an [aligned]-byte allocation, in [GROWTH_PAGES]-page steps.
     *
     * Deliberately its own function, called from a cold branch: see the note on [allocateOffset] for
     * why that placement is what keeps the Kotlin/WASM optimizer bug away, and [initializeMemory] for
     * the precedent this follows.
     *
     * @return true if the pool can now satisfy [aligned] bytes
     */
    private fun growMemory(aligned: Int): Boolean {
        val neededPages = (aligned + PAGE_SIZE - 1) / PAGE_SIZE
        // Offsets are Ints, so the pool cannot usefully extend past Int.MAX_VALUE; and it must stay
        // under the configured ceiling. Whichever binds first caps this step.
        val addressablePages = (Int.MAX_VALUE / PAGE_SIZE) - (heapEnd / PAGE_SIZE)
        val underCeilingPages = (LinearMemoryConfig.maxSizeMB * PAGES_PER_MB) - (poolBytes / PAGE_SIZE)
        val allowedPages = if (underCeilingPages < addressablePages) underCeilingPages else addressablePages

        var granted = false
        if (allowedPages >= neededPages) {
            var pages = if (neededPages > GROWTH_PAGES) neededPages else GROWTH_PAGES
            // Take a partial step if that is all the cap allows.
            if (pages > allowedPages) pages = allowedPages
            granted = claimPages(pages)
        }
        return granted
    }

    /**
     * Ask the engine for [pages] more pages and fold the grant into the pool.
     *
     * @return true if the engine granted them
     */
    private fun claimPages(pages: Int): Boolean {
        val previousSizePages = jsMemoryGrow(pages)
        if (previousSizePages < 0) return false

        val grantedStart = previousSizePages * PAGE_SIZE
        if (grantedStart == heapEnd) {
            // Contiguous with the pool, which is the normal case: just extend it.
            heapEnd = grantedStart + pages * PAGE_SIZE
        } else {
            // Something else grew linear memory since we last did, so the grant is not adjacent to
            // the pool. Re-base onto the new region rather than spanning the gap: `reset` zeroes
            // [heapBase, nextOffset) and `free` bounds-checks against heapBase, and neither may be
            // allowed to reach across memory we do not own. Blocks already handed out stay valid to
            // read and write; releasing one now fails the bounds check and leaks it, which is the
            // safe direction. This branch is defensive — nothing else grows linear memory today.
            heapBase = grantedStart
            nextOffset = grantedStart
            heapEnd = grantedStart + pages * PAGE_SIZE
            // Every parked block lies in the region we just left, and the invariant the rest of this
            // object relies on is that a free-list entry is always within [heapBase, nextOffset).
            // Drop them: they are leaked either way (`free` would reject them on the bounds check),
            // but a dropped block is never handed back out as an offset outside the current pool.
            smallHeads.fill(NO_BLOCK)
            largeHeads.clear()
            freeListBytes = 0
        }
        poolBytes += pages * PAGE_SIZE
        return true
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
        val aligned = (size + ALIGN_8_MASK) and ALIGN_8_MASK.inv()
        // Reject anything that was never handed out by this allocator (e.g. a wrapped external
        // address). Blocks are always fully below the watermark at the time they are freed.
        val ours = initialized && aligned > 0 && offset >= heapBase && offset + aligned <= nextOffset
        if (!ours) return

        if (offset + aligned == nextOffset) {
            // Top of heap: give the bytes back outright.
            //
            // This can never strand a free-list entry above the new watermark. Allocations do not
            // overlap, so any parked block X satisfies either X + sizeX <= offset (stays below the
            // rewound watermark) or X >= nextOffset (impossible — X was handed out below it).
            nextOffset = offset
        } else {
            // Push onto the intrusive chain: the old head goes into the block's first 4 bytes.
            Pointer(offset.toUInt()).storeInt(headOf(aligned))
            setHead(aligned, offset)
            freeListBytes += aligned
        }
    }

    /**
     * Claim the initial pool, based above both the current top of linear memory and the runtime's
     * scratch reserve — see [runtimeScratchReserveBytes] for why the second bound is not optional.
     *
     * Called from a cold branch of [allocateOffset] exactly once; the `@JsFun` calls are why it has
     * to be its own function (see the note on [allocateOffset]).
     */
    private fun initializeMemory() {
        val sizeBytes = jsMemorySize()
        // Grow far enough that [base, base + initial) exists, where base clears the reserve.
        val base = if (sizeBytes > RUNTIME_SCRATCH_RESERVE_BYTES) sizeBytes else RUNTIME_SCRATCH_RESERVE_BYTES
        val pagesToGrow = (base + initialPages * PAGE_SIZE - sizeBytes) / PAGE_SIZE
        val previousSizePages = jsMemoryGrow(pagesToGrow)
        if (previousSizePages < 0) {
            throw OutOfMemoryError("Failed to grow WASM memory for buffer allocation")
        }
        // Re-derive from what the engine actually reported rather than from the pre-grow read.
        val grantedBase = previousSizePages * PAGE_SIZE
        heapBase = if (grantedBase > RUNTIME_SCRATCH_RESERVE_BYTES) grantedBase else RUNTIME_SCRATCH_RESERVE_BYTES
        nextOffset = heapBase
        heapEnd = grantedBase + pagesToGrow * PAGE_SIZE
        poolBytes = if (heapEnd > heapBase) heapEnd - heapBase else 0
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
            poolBytes = poolBytes,
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

    /**
     * 1 MiB floor under [heapBase] — the region the Kotlin/Wasm runtime bump-allocates its interop
     * scratch from on every `withScopedMemoryAllocator` scope. See [runtimeScratchReserveBytes].
     */
    private const val RUNTIME_SCRATCH_RESERVE_BYTES = 1024 * 1024

    private const val HEX_RADIX = 16

    /** `log2(8)` — turns an 8-byte-aligned size into its [smallHeads] index. */
    private const val ALIGN_8_SHIFT = 3

    /** Free-list size classes are tracked in an array up to this size; above it, in a hash map. */
    private const val SMALL_MAX_SIZE = 64 * 1024

    private const val SMALL_CLASS_COUNT = SMALL_MAX_SIZE ushr ALIGN_8_SHIFT

    /** Minimum step [growMemory] takes, so a churning workload does not call into JS per allocation. */
    private const val GROWTH_PAGES = 256 // 16 MB

    private const val BYTES_PER_MB = 1024 * 1024

    private const val PAGES_PER_MB = BYTES_PER_MB / PAGE_SIZE
}
