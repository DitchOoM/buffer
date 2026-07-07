package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/** SHA-256 digest length in bytes (FIPS 180-4). */
const val SHA256_DIGEST_BYTES = 32

/** SHA-256 internal block length in bytes. */
const val SHA256_BLOCK_BYTES = 64

/**
 * Incremental SHA-256 (FIPS 180-4).
 *
 * Backed by each platform's native crypto subsystem where a synchronous API exists
 * (JCA `MessageDigest` on JVM/Android, CommonCrypto `CC_SHA256` on Apple) and by a
 * synchronous pure-Kotlin implementation on js/wasmJs (WebCrypto's `SubtleCrypto.digest`
 * is async-only and cannot satisfy this synchronous contract).
 *
 * Feed bytes with [update] (call repeatedly to hash `keyMaterial ‖ message` without
 * first concatenating it), then [digestInto] to write the 32-byte result into a
 * caller-owned destination. Reading is non-destructive: [update] consumes a `slice()`
 * of the input, leaving the source buffer's position untouched.
 *
 * Not thread-safe — use one instance per digest. [digestInto] wipes the instance's
 * scratch before returning and finalizes the digest; the instance is one-shot on every
 * platform, so [update] or [digestInto] after finalization throws [IllegalStateException].
 */
expect class Sha256Digest() : AutoCloseable {
    /** Absorbs the remaining bytes of [input] (non-destructive). Returns `this` for chaining. */
    fun update(input: ReadBuffer): Sha256Digest

    /**
     * Finalizes and writes [SHA256_DIGEST_BYTES] bytes into [dest] at its current
     * position, advancing it. [dest] must have at least [SHA256_DIGEST_BYTES] remaining.
     * Releases the digest state; any further [update]/[digestInto] throws [IllegalStateException].
     */
    fun digestInto(dest: WriteBuffer)

    /**
     * Releases the digest state without producing a result — for a digest abandoned before
     * [digestInto] (which releases the state itself). Idempotent; a no-op after [digestInto].
     */
    override fun close()
}

/**
 * One-shot SHA-256 of [input]'s remaining bytes, written into [dest] (zero allocation).
 *
 * [dest] must have at least [SHA256_DIGEST_BYTES] remaining. Use a secure/deterministic
 * destination (see [secure][com.ditchoom.buffer.crypto.secure]) if the digest must be
 * wiped after use.
 */
fun sha256(
    input: ReadBuffer,
    dest: WriteBuffer,
) {
    require(dest.remaining() >= SHA256_DIGEST_BYTES) {
        "dest needs $SHA256_DIGEST_BYTES bytes remaining, has ${dest.remaining()}"
    }
    Sha256Digest().update(input).digestInto(dest)
}

/**
 * One-shot SHA-256 of [input]'s remaining bytes, returning a freshly allocated,
 * read-ready buffer. The result is allocated via [factory].
 */
fun sha256(
    input: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    val out: PlatformBuffer = factory.allocate(SHA256_DIGEST_BYTES)
    Sha256Digest().update(input).digestInto(out)
    out.resetForRead()
    return out
}
