package com.ditchoom.buffer

// Fast 64-bit content digest over a byte region, for hash-table bucketing.
//
// This is intentionally separate from bufferHashCode(): that one backs the equals()/hashCode()
// contract and must stay the documented stable 31-multiplier rolling hash. This digest is a
// faster, non-contractual FNV-1a-64 that mixes 8 bytes per step.
//
// Like contentEquals()/mismatch(), it reads through getLong()/getByte(), so its numeric value is
// consistent for buffers that share the same byteOrder (identical bytes + same byteOrder -> same
// digest). That is exactly what bucketing needs; collisions are resolved by an explicit byte
// comparison (regionEquals) at the call site.

internal const val FNV64_OFFSET_BASIS: Long = -3750763034362895579L // 0xcbf29ce484222325
internal const val FNV64_PRIME: Long = 0x100000001b3L

/**
 * FNV-1a-64 over an absolute byte range, mixing whole 8-byte words then the byte tail.
 *
 * @param seed starting hash state (use [FNV64_OFFSET_BASIS] for a fresh digest)
 * @param offset absolute index of the first byte
 * @param length number of bytes to hash
 * @param getLong absolute 8-byte accessor
 * @param getByte absolute byte accessor
 */
internal inline fun fnv1aHashRange(
    seed: Long,
    offset: Int,
    length: Int,
    getLong: (Int) -> Long,
    getByte: (Int) -> Byte,
): Long {
    var h = seed
    var i = 0
    while (i + Long.SIZE_BYTES <= length) {
        h = (h xor getLong(offset + i)) * FNV64_PRIME
        i += Long.SIZE_BYTES
    }
    while (i < length) {
        h = (h xor (getByte(offset + i).toLong() and BufferConstants.BYTE_MASK.toLong())) * FNV64_PRIME
        i++
    }
    return h
}

// ReadBuffer.hashRange(offset, length) is a member of ReadBuffer (default impl folds via
// fnv1aHashRange below); NativeBuffer overrides it with a single cinterop C call (buf_fnv1a_64) so the
// whole digest loop runs with raw pointer arithmetic instead of per-element CPointer materialization.

/**
 * Validates that `[offset, offset + length)` lies within this buffer, throwing the same
 * [BufferUnderflowException] the per-element accessors would. Bulk primitives call this once up front
 * so their inner loop can use the unchecked accessors ([getUnchecked]/[getLongUnchecked]).
 *
 * Note the bulk word reads (`getLongUnchecked`) only advance while a full 8-byte word fits inside
 * `length`, so every access stays within `[offset, offset + length)` — one range check covers them all.
 */
internal fun ReadBuffer.requireRange(
    offset: Int,
    length: Int,
) {
    if (offset < 0 || length < 0 || offset + length > limit()) {
        throw BufferUnderflowException(
            "range [$offset, ${offset + length}) exceeds limit ${limit()}",
        )
    }
}

/**
 * Fast 64-bit content digest over the remaining bytes (`position()` until `limit()`).
 * Convenience for [hashRange]; does not change [position].
 */
fun ReadBuffer.hash64(): Long = hashRange(position(), remaining())

/**
 * True if `length` bytes of this buffer starting at [thisOffset] are byte-identical to `length`
 * bytes of [other] starting at [otherOffset].
 *
 * Uses the shared bulk 8-byte comparison ([bulkCompareEquals]). Reads through absolute accessors
 * only, so it does not change the [position] of either buffer.
 */
fun ReadBuffer.regionEquals(
    thisOffset: Int,
    other: ReadBuffer,
    otherOffset: Int,
    length: Int,
): Boolean {
    requireRange(thisOffset, length)
    other.requireRange(otherOffset, length)
    return bulkCompareEquals(
        thisPos = thisOffset,
        otherPos = otherOffset,
        length = length,
        getLong = { getLongUnchecked(it) },
        otherGetLong = { other.getLongUnchecked(it) },
        getByte = { getUnchecked(it) },
        otherGetByte = { other.getUnchecked(it) },
    )
}
