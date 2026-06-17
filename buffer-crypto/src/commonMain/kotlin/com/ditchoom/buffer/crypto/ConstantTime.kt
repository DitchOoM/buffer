package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer

/**
 * Constant-time comparison of the remaining bytes of two buffers.
 *
 * Returns `true` iff both buffers have the same number of remaining bytes and every
 * byte is equal. Unlike [ReadBuffer.contentEquals], this **never short-circuits on the
 * first differing byte** — the running time depends only on the input length, not on
 * how many leading bytes happen to match. Use it for every secret-dependent comparison
 * (MAC verification, AEAD tag checks, comparing a derived value against an attacker-
 * supplied one) so the implementation does not leak a timing oracle.
 *
 * The comparison is non-destructive: neither buffer's position is advanced (reads use
 * absolute indexing).
 *
 * Note: the *length* of the two buffers is treated as non-secret — a length mismatch
 * returns `false` immediately. This is the standard contract: tag/MAC lengths are fixed
 * and public, only their contents are secret. Do not rely on this to hide a length.
 */
fun ReadBuffer.constantTimeEquals(other: ReadBuffer): Boolean {
    val n = remaining()
    if (n != other.remaining()) return false
    val aStart = position()
    val bStart = other.position()
    var diff = 0
    for (i in 0 until n) {
        diff = diff or (get(aStart + i).toInt() xor other.get(bStart + i).toInt())
    }
    return diff == 0
}
