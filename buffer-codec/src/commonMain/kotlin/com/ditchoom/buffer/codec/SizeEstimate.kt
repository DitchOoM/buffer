package com.ditchoom.buffer.codec

import kotlin.jvm.JvmInline

/**
 * Result of estimating the encoded size of a message.
 *
 * Used for buffer pre-allocation. [Exact] enables precise allocation (no wasted bytes).
 * [UnableToPrecalculate] falls back to a default buffer size.
 */
sealed interface SizeEstimate {
    /** Exact encoded size in bytes. Enables precise buffer allocation. */
    @JvmInline
    value class Exact(
        val bytes: Int,
    ) : SizeEstimate

    /** Cannot determine size without encoding. Use a default or growable buffer. */
    data object UnableToPrecalculate : SizeEstimate
}
