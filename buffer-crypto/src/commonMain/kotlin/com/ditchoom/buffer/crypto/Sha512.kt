package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/** SHA-512 digest length in bytes (FIPS 180-4). */
const val SHA512_DIGEST_BYTES = 64

/** SHA-512 internal block length in bytes. */
const val SHA512_BLOCK_BYTES = 128

/**
 * Incremental SHA-512 (FIPS 180-4).
 *
 * Backed by each platform's native crypto subsystem where a synchronous API exists
 * (JCA `MessageDigest` on JVM/Android, CommonCrypto `CC_SHA512` on Apple) and by a
 * synchronous pure-Kotlin implementation on js/wasmJs (WebCrypto's `SubtleCrypto.digest`
 * is async-only and cannot satisfy this synchronous contract).
 *
 * Feed bytes with [update] (call repeatedly to hash `a ‖ b` without first concatenating),
 * then [digestInto] to write the 64-byte result into a caller-owned destination. Reading is
 * non-destructive: [update] consumes a `slice()` of the input, leaving the source untouched.
 *
 * Not thread-safe — use one instance per digest. The instance is one-shot on every platform:
 * [update] or [digestInto] after finalization throws [IllegalStateException].
 */
expect class Sha512Digest() : AutoCloseable {
    /** Absorbs the remaining bytes of [input] (non-destructive). Returns `this` for chaining. */
    fun update(input: ReadBuffer): Sha512Digest

    /**
     * Finalizes and writes [SHA512_DIGEST_BYTES] bytes into [dest] at its current
     * position, advancing it. [dest] must have at least [SHA512_DIGEST_BYTES] remaining.
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
 * One-shot SHA-512 of [input]'s remaining bytes, written into [dest] (zero allocation).
 *
 * [dest] must have at least [SHA512_DIGEST_BYTES] remaining.
 */
fun sha512(
    input: ReadBuffer,
    dest: WriteBuffer,
) {
    require(dest.remaining() >= SHA512_DIGEST_BYTES) {
        "dest needs $SHA512_DIGEST_BYTES bytes remaining, has ${dest.remaining()}"
    }
    Sha512Digest().update(input).digestInto(dest)
}

/**
 * One-shot SHA-512 of [input]'s remaining bytes, returning a freshly allocated,
 * read-ready buffer allocated via [factory].
 */
fun sha512(
    input: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    val out: PlatformBuffer = factory.allocate(SHA512_DIGEST_BYTES)
    Sha512Digest().update(input).digestInto(out)
    out.resetForRead()
    return out
}
