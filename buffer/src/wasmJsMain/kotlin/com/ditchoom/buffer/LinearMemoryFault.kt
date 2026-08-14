package com.ditchoom.buffer

/**
 * A structural fault in wasm linear memory that [LinearMemoryAllocator] detected and **contained**.
 *
 * Every subclass is raised at the moment of detection, with the allocator already returned to a
 * usable state — so catching one and continuing is defined behaviour, not a gamble. That is the
 * whole point of raising it rather than letting the condition run on: the alternative is a
 * `RuntimeError: memory access out of bounds` from `Pointer.loadInt`, whose stack names the
 * allocator and nothing about the write that caused it.
 *
 * ## What "contained" means, and what it does not
 *
 * Contained means *the allocator* is consistent: it will keep serving allocations, and it will
 * detect a further breach. It does not mean your data is intact. Both faults are evidence that
 * something wrote into memory the pool had handed out, so a buffer allocated before the fault may
 * hold bytes somebody else put there. Recovery therefore means discarding state derived from
 * buffers, not merely retrying the allocation.
 *
 * ## Branching
 *
 * Branch on the type and read the fields; do not parse [message]. The message is a short, derived
 * summary for logs, and its wording is not API — the typed fields are.
 *
 * ```kotlin
 * try {
 *     process(BufferFactory.Default.allocate(size))
 * } catch (fault: LinearMemoryFault.RuntimeScratchBreach) {
 *     // The reserve was too small for this workload's interop scratch. Raising it needs a restart,
 *     // since it is fixed at first allocation.
 *     telemetry.report(fault.overwrittenWords, fault.reserveBytes)
 *     dropCachedBuffers()
 * } catch (fault: LinearMemoryFault.FreeListCorruption) {
 *     // A stale write through a released view. The size class was dropped; allocation continues.
 *     telemetry.report(fault.site, fault.offset, fault.sizeClassBytes)
 *     dropCachedBuffers()
 * }
 * ```
 *
 * Extends [IllegalStateException] so callers written against the previous, untyped signal keep
 * compiling and keep catching.
 *
 * @property heapBase base of the pool when the fault was detected
 * @property nextOffset bump watermark when the fault was detected
 * @property heapEnd end of the pool when the fault was detected
 * @property poolBytes linear memory claimed from the engine when the fault was detected
 */
sealed class LinearMemoryFault(
    val heapBase: Int,
    val nextOffset: Int,
    val heapEnd: Int,
    val poolBytes: Int,
    private val summary: String,
) : IllegalStateException() {
    override val message: String
        get() = "$summary [heapBase=$heapBase nextOffset=$nextOffset heapEnd=$heapEnd poolBytes=$poolBytes]"

    /**
     * The Kotlin/Wasm runtime's interop scratch grew across the pool's base.
     *
     * Every top-level `withScopedMemoryAllocator` scope bump-allocates upward from address 0, so a
     * scope whose total allocation exceeds
     * [runtimeScratchReserveBytes][LinearMemoryAllocator.runtimeScratchReserveBytes] writes through
     * memory the pool has handed out. The canary at the pool's base is what notices.
     *
     * **Remedy, in order of preference.** Stage large payloads through `BufferFactory` instead of
     * the runtime's scoped allocator — the payload then lives in pool memory and the collision
     * cannot arise. Failing that, raise the reserve with
     * `PlatformBuffer.configureWasmMemory(runtimeScratchReserveMB = ...)`, which must happen before
     * the first allocation and therefore needs a restart. A reserve is a floor, never a guarantee:
     * scratch is as large as whatever is staged through it.
     *
     * The canary is re-stamped before this is thrown, so a later breach is detected too.
     *
     * @property overwrittenWords how many canary words were clobbered
     * @property totalWords how many canary words there are
     * @property reserveBytes the reserve that proved too small
     */
    class RuntimeScratchBreach internal constructor(
        val overwrittenWords: Int,
        val totalWords: Int,
        val reserveBytes: Int,
        heapBase: Int,
        nextOffset: Int,
        heapEnd: Int,
        poolBytes: Int,
    ) : LinearMemoryFault(
            heapBase,
            nextOffset,
            heapEnd,
            poolBytes,
            "Kotlin/Wasm runtime scratch reached the buffer pool: " +
                "$overwrittenWords/$totalWords canary words overwritten, reserve=$reserveBytes",
        )

    /**
     * A free-list entry pointed outside the pool.
     *
     * The free list is intrusive — a released block's next-link lives in its own first four bytes —
     * so any stale write into a released block lands on a link. Usual causes: a `slice()` or
     * `wrapNativeAddress()` view used after its owner was released, a retained `nativeAddress`, or
     * runtime scratch reaching the pool (which [RuntimeScratchBreach] reports directly when it
     * crosses the base).
     *
     * The affected size class's chain is dropped before this is thrown, so allocation continues.
     * Those blocks are leaked for the life of the process: the links are precisely what is
     * untrustworthy, so the chain cannot be walked to re-park them.
     *
     * @property site whether the bad offset came from the class's head slot or from a block's link
     * @property offset the offset that does not point into the pool
     * @property sizeClassBytes the size class whose chain this was
     * @property inBlock the block the link was read out of, or -1 when [site] is [Site.Head]
     */
    class FreeListCorruption internal constructor(
        val site: Site,
        val offset: Int,
        val sizeClassBytes: Int,
        val inBlock: Int,
        heapBase: Int,
        nextOffset: Int,
        heapEnd: Int,
        poolBytes: Int,
    ) : LinearMemoryFault(
            heapBase,
            nextOffset,
            heapEnd,
            poolBytes,
            "free list corrupt at $site: offset=$offset sizeClass=$sizeClassBytes inBlock=$inBlock",
        ) {
        /** Where the bad offset was read from. */
        enum class Site {
            /** The size class's head slot, which the allocator owns. */
            Head,

            /** The next-link inside a released block, which is linear memory anyone can write. */
            Link,
        }
    }
}
