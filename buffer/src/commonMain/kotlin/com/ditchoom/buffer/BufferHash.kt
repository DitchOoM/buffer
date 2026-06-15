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
    while (i + 8 <= length) {
        h = (h xor getLong(offset + i)) * FNV64_PRIME
        i += 8
    }
    while (i < length) {
        h = (h xor (getByte(offset + i).toLong() and 0xFF)) * FNV64_PRIME
        i++
    }
    return h
}

/**
 * Fast 64-bit content digest over the absolute byte range `[offset, offset + length)`.
 *
 * FNV-1a-64, mixing whole 8-byte words then the byte tail (see [fnv1aHashRange]). Intended for
 * hash-table bucketing where collisions are resolved by an explicit [regionEquals]. The value is
 * consistent across buffers sharing the same [byteOrder]; it does not change [position].
 */
fun ReadBuffer.hashRange(
    offset: Int,
    length: Int,
): Long = fnv1aHashRange(FNV64_OFFSET_BASIS, offset, length, { getLong(it) }, { get(it) })

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
): Boolean =
    bulkCompareEquals(
        thisPos = thisOffset,
        otherPos = otherOffset,
        length = length,
        getLong = { getLong(it) },
        otherGetLong = { other.getLong(it) },
        getByte = { get(it) },
        otherGetByte = { other.get(it) },
    )
