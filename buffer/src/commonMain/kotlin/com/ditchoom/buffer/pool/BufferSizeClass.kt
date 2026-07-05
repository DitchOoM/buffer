package com.ditchoom.buffer.pool

/**
 * Power-of-two size classes for pooled buffer allocations.
 *
 * Pools round every allocation up to a size class and index their cached buffers by
 * class, for two reasons:
 * - **Allocator fragmentation:** exact odd-sized allocations (e.g. an assembled
 *   8 MiB + 29 network message) produce unique block sizes that freelist allocators
 *   cannot coalesce or reuse. On Android/ART this permanently fragments the Large
 *   Object Space and the non-moving malloc space until MB-scale allocations fail with
 *   tens of MB free (see ANDROID_ART_ALLOCATOR.md). Rounding keeps live block sizes
 *   commensurable: power-of-two classes up to 1 MiB, 1 MiB multiples above (capping
 *   round-up waste at 1 MiB instead of doubling large allocations).
 * - **Size-aware reuse:** bucketing by class lets acquire() pop a buffer that is
 *   guaranteed to fit instead of freeing and reallocating on a wrong-sized pop.
 *
 * Invariant: a buffer stored in bucket `k` has `capacity >= 1 shl k` (floor log2), and a
 * request routed to bucket `k` needs at most `1 shl k` bytes (ceil log2), so any buffer
 * found in bucket `k` or higher satisfies the request. The single exception is bucket 0,
 * which also holds zero-capacity buffers; acquire() keeps a defensive capacity check.
 */
internal object BufferSizeClass {
    /** Largest power-of-two size representable as a positive Int (2^30 = 1 GiB). */
    private const val MAX_POW2: Int = 1 shl 30

    /**
     * Above this size, rounding switches from next-power-of-two to next-multiple-of-this.
     * Power-of-two rounding wastes up to 100% (a 16 MiB + 29 request would allocate
     * 32 MiB), which doubles peak memory for large-message workloads — enough to tip
     * heap-constrained Android over the edge (Autobahn 9.1.6 echoes a 16 MB message on
     * a 576 MB-cap device). Multiples of a 1 MiB quantum keep every large block size
     * commensurable — freelist allocators can coalesce and re-split them freely, which
     * is what actually prevents fragmentation — while capping waste at 1 MiB.
     */
    private const val LARGE_GRANULARITY: Int = 1 shl 20

    /** One bucket per power-of-two exponent of a non-negative Int capacity. */
    const val BUCKET_COUNT: Int = 32

    /**
     * Rounds [size] up to its size class: the next power of two up to [LARGE_GRANULARITY],
     * the next [LARGE_GRANULARITY] multiple up to [MAX_POW2], or the exact size above
     * [MAX_POW2] (where rounding could overflow Int).
     *
     * Requests above [MAX_POW2] map to bucket 31, which no capacity's floor log2 can
     * reach — they always miss and allocate exactly, and are (deliberately) never
     * served from the pool. Their released buffers land in bucket 30 and remain
     * reusable for any request up to [MAX_POW2].
     *
     * Note that a non-power-of-two rounded size (e.g. 17 MiB) is stored under its floor
     * log2 bucket, one below where an equal-sized request starts searching — so exact
     * same-size churn above [LARGE_GRANULARITY] misses the pool and reallocates. That
     * matches the pre-size-class pool's behavior for mismatched pops; commensurable
     * block sizes make the free/realloc cycle fragmentation-safe.
     */
    fun roundUp(size: Int): Int =
        when {
            size <= 1 -> size.coerceAtLeast(0)
            size > MAX_POW2 -> size
            size > LARGE_GRANULARITY ->
                (size + LARGE_GRANULARITY - 1) and (LARGE_GRANULARITY - 1).inv()
            else -> 1 shl bucketForRequest(size)
        }

    /** Index of the smallest bucket whose buffers are guaranteed to satisfy [size]. */
    fun bucketForRequest(size: Int): Int =
        if (size <= 1) {
            0
        } else {
            (Int.SIZE_BITS - (size - 1).countLeadingZeroBits()).coerceAtMost(BUCKET_COUNT - 1)
        }

    /** Index of the bucket a buffer of [capacity] is stored in (floor log2). */
    fun bucketForCapacity(capacity: Int): Int =
        if (capacity <= 1) {
            0
        } else {
            (Int.SIZE_BITS - 1) - capacity.countLeadingZeroBits()
        }
}
