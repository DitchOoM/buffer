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
 * Classes are totally ordered by size: bucket `k` holds buffers whose capacity is at least
 * the size of class `k`. A request is routed to the smallest class that fits it and a buffer
 * is filed under the largest class it fills, so any buffer found in the request's bucket or
 * a higher one satisfies the request. A buffer the pool allocated has exactly its class's
 * size, so it is filed under the class a request of the same size searches. The single
 * exception is bucket 0, which also holds zero-capacity buffers; acquire() keeps a defensive
 * capacity check.
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

    /** Power-of-two classes `0..20` (1 byte to 1 MiB), then one class per MiB multiple up to 1 GiB. */
    private const val POW2_CLASS_COUNT: Int = 21

    /** Class index of the 1 MiB multiple `n` (`n >= 1`): `n = 1` is the last power-of-two class. */
    private const val LARGE_CLASS_OFFSET: Int = POW2_CLASS_COUNT - 2

    /** Number of size classes; a bucket index at or above this is searched by no request. */
    const val BUCKET_COUNT: Int = LARGE_CLASS_OFFSET + MAX_POW2 / LARGE_GRANULARITY + 1

    /**
     * Rounds [size] up to its size class: the next power of two up to [LARGE_GRANULARITY],
     * the next [LARGE_GRANULARITY] multiple up to [MAX_POW2], or the exact size above
     * [MAX_POW2] (where rounding could overflow Int).
     *
     * Requests above [MAX_POW2] have no class: they search no bucket, always allocate
     * exactly, and are never served from the pool. Their released buffers are filed under
     * the largest class and remain reusable for any request up to [MAX_POW2].
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
        when {
            size <= 1 -> 0
            size > MAX_POW2 -> BUCKET_COUNT
            size > LARGE_GRANULARITY -> LARGE_CLASS_OFFSET + (size + LARGE_GRANULARITY - 1) / LARGE_GRANULARITY
            else -> Int.SIZE_BITS - (size - 1).countLeadingZeroBits()
        }

    /** Index of the bucket a buffer of [capacity] is stored in: the largest class it fills. */
    fun bucketForCapacity(capacity: Int): Int =
        when {
            capacity <= 1 -> 0
            capacity < LARGE_GRANULARITY -> (Int.SIZE_BITS - 1) - capacity.countLeadingZeroBits()
            else -> (LARGE_CLASS_OFFSET + capacity / LARGE_GRANULARITY).coerceAtMost(BUCKET_COUNT - 1)
        }
}
