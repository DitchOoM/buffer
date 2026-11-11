package com.ditchoom.buffer.pool

/**
 * Geometric size classes for pooled buffer allocations.
 *
 * Pools round every allocation up to a size class and index their cached buffers by
 * class, for two reasons:
 * - **Allocator fragmentation:** exact odd-sized allocations (e.g. an assembled
 *   8 MiB + 29 network message) produce unique block sizes that freelist allocators
 *   cannot coalesce or reuse. On Android/ART this permanently fragments the Large
 *   Object Space and the non-moving malloc space until MB-scale allocations fail with
 *   tens of MB free (see ANDROID_ART_ALLOCATOR.md).
 * - **Size-aware reuse:** bucketing by class lets acquire() pop a buffer that is
 *   guaranteed to fit instead of freeing and reallocating on a wrong-sized pop.
 *
 * Classes are one per power of two up to [LARGE_THRESHOLD] (1 MiB), then [STEPS_PER_DOUBLING]
 * evenly spaced classes per doubling up to [MAX_POW2] (1.25, 1.5, 1.75 and 2.0 times each
 * power of two), which caps round-up waste at 25% instead of doubling large allocations
 * (a 16 MiB + 29 request allocates 20 MiB, not 32 MiB).
 *
 * A request is routed to the smallest class that fits it and a buffer is filed under the
 * largest class it fills. A pooled buffer's capacity is always exactly a class capacity,
 * so both lookups give the same class: a released buffer is found again by the next request
 * of the same size. The single exception is bucket 0, which also holds zero-capacity
 * buffers; acquire() keeps a defensive capacity check.
 */
internal object BufferSizeClass {
    /** Largest power-of-two size representable as a positive Int (2^30 = 1 GiB). */
    private const val MAX_POW2: Int = 1 shl 30

    /** Exponent of [LARGE_THRESHOLD]; classes at and below it are powers of two. */
    private const val LARGE_EXPONENT: Int = 20

    /** Above this size classes are subdivided: 1 MiB. */
    private const val LARGE_THRESHOLD: Int = 1 shl LARGE_EXPONENT

    private const val STEPS_PER_DOUBLING: Int = 4
    private const val STEP_SHIFT: Int = 2 // log2(STEPS_PER_DOUBLING)

    /** Index of the 1 GiB class, the last one. */
    private const val LAST_CLASS: Int = LARGE_EXPONENT + STEPS_PER_DOUBLING * (30 - LARGE_EXPONENT)

    /**
     * Number of size classes; a bucket index at or above this is searched by no request.
     */
    const val BUCKET_COUNT: Int = LAST_CLASS + 1

    /**
     * Rounds [size] up to its size class capacity, or returns the exact size above [MAX_POW2]
     * (where rounding could overflow Int).
     *
     * Requests above [MAX_POW2] have no class: they search no bucket, always allocate
     * exactly, and are never served from the pool. Their released buffers are filed under
     * the largest class and remain reusable for any request up to [MAX_POW2].
     */
    fun roundUp(size: Int): Int =
        when {
            size <= 1 -> size.coerceAtLeast(0)
            size > MAX_POW2 -> size
            else -> capacityOf(bucketForRequest(size))
        }

    /** Index of the smallest bucket whose buffers are guaranteed to satisfy [size]. */
    fun bucketForRequest(size: Int): Int =
        when {
            size <= 1 -> 0
            size > MAX_POW2 -> BUCKET_COUNT
            size <= LARGE_THRESHOLD -> Int.SIZE_BITS - (size - 1).countLeadingZeroBits()
            else -> {
                val exponent = (Int.SIZE_BITS - 1) - (size - 1).countLeadingZeroBits()
                val step = 1 shl (exponent - STEP_SHIFT)
                val stepsAbove = (size - (1 shl exponent) + step - 1) shr (exponent - STEP_SHIFT)
                LARGE_EXPONENT + STEPS_PER_DOUBLING * (exponent - LARGE_EXPONENT) + stepsAbove
            }
        }

    /** Index of the bucket a buffer of [capacity] is stored in: the largest class it fills. */
    fun bucketForCapacity(capacity: Int): Int =
        when {
            capacity <= 1 -> 0
            capacity < LARGE_THRESHOLD -> (Int.SIZE_BITS - 1) - capacity.countLeadingZeroBits()
            capacity >= MAX_POW2 -> LAST_CLASS
            else -> {
                val exponent = (Int.SIZE_BITS - 1) - capacity.countLeadingZeroBits()
                val stepsAbove = (capacity - (1 shl exponent)) shr (exponent - STEP_SHIFT)
                LARGE_EXPONENT + STEPS_PER_DOUBLING * (exponent - LARGE_EXPONENT) + stepsAbove
            }
        }

    /** Capacity of the class at [bucket] (`0..BUCKET_COUNT - 1`). */
    private fun capacityOf(bucket: Int): Int =
        if (bucket <= LARGE_EXPONENT) {
            1 shl bucket
        } else {
            val offset = bucket - LARGE_EXPONENT - 1
            val exponent = LARGE_EXPONENT + offset / STEPS_PER_DOUBLING
            val stepsAbove = offset % STEPS_PER_DOUBLING + 1
            (1 shl exponent) + (stepsAbove shl (exponent - STEP_SHIFT))
        }
}
