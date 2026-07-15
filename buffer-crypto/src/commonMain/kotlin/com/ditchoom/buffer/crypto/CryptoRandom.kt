package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.WriteBuffer
import kotlin.random.Random

/**
 * Fills the remaining bytes of [dest] (position to limit) with cryptographically secure
 * random bytes, advancing its position to the limit.
 *
 * Backed by each platform's CSPRNG: `java.security.SecureRandom` (JVM/Android),
 * `SecRandomCopyBytes` (Apple), and `crypto.getRandomValues` (js/wasmJs — this WebCrypto
 * call is synchronous, unlike `SubtleCrypto`).
 */
expect fun cryptoRandomInto(dest: WriteBuffer)

/**
 * Returns a freshly allocated, read-ready buffer of [size] cryptographically secure
 * random bytes, allocated via [factory].
 */
fun cryptoRandom(
    size: Int,
    factory: BufferFactory = BufferFactory.Default,
): PlatformBuffer {
    val out = factory.allocate(size)
    cryptoRandomInto(out)
    out.resetForRead()
    return out
}

/**
 * A [Random] whose every draw is pulled fresh from the platform CSPRNG — the same source as
 * [cryptoRandomInto] — rather than from a seeded PRNG. Reach for it wherever an API takes a
 * `kotlin.random.Random` but the result must be unpredictable: minting IDs, invite codes, tokens, or
 * nonces. You inherit `Random`'s unbiased helpers ([Random.nextInt] with a bound, [Random.nextLong],
 * `List.random`, `MutableList.shuffle`) over secure bytes, instead of hand-rolling range reduction
 * on top of [cryptoRandom] and reintroducing modulo bias.
 *
 * Not seedable and not reproducible by design — [Random.nextBits] draws new entropy on every call, so
 * the instance carries no seed state and is safe to share across threads. It is a `Random` for its
 * algebra, not for repeatability; for deterministic test data, use a seeded [Random] instead.
 */
val secureRandom: Random = SecureRandomSource

private object SecureRandomSource : Random() {
    override fun nextBits(bitCount: Int): Int =
        // Kotlin's takeUpperBits idiom: keep the top `bitCount` bits of a full 32-bit draw, and mask
        // the whole result to 0 when bitCount == 0. `-bitCount shr (SIZE_BITS - 1)` broadcasts the sign
        // bit — 0 for bitCount == 0 (all bits cleared), -1 for bitCount in 1..32 (identity). A bare
        // `ushr (SIZE_BITS - bitCount)` would return the full int for bitCount == 0, since Kotlin masks
        // shift counts mod 32.
        (cryptoRandomInt() ushr (Int.SIZE_BITS - bitCount)) and (-bitCount shr (Int.SIZE_BITS - 1))
}

/**
 * One cryptographically secure random [Int] drawn straight from the platform CSPRNG, without
 * allocating a destination buffer: JVM/Android draw from a shared [java.security.SecureRandom],
 * Apple/Linux fill a stack-scoped `Int`, and js/wasm read a single `Int32Array` element. Backs
 * [secureRandom]'s `nextBits`; for more than a few bytes prefer [cryptoRandomInto].
 */
internal expect fun cryptoRandomInt(): Int
