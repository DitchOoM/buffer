package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/** SHA-384 digest length in bytes (FIPS 180-4). */
const val SHA384_DIGEST_BYTES = 48

/** SHA-384 internal block length in bytes (shares SHA-512's 1024-bit block). */
const val SHA384_BLOCK_BYTES = 128

/**
 * Incremental SHA-384 (FIPS 180-4) — SHA-512 with a distinct IV, truncated to 384 bits.
 *
 * Backed by each platform's native crypto subsystem (JCA `MessageDigest` on JVM/Android,
 * CommonCrypto `CC_SHA384` on Apple) and by a synchronous pure-Kotlin implementation on
 * js/wasmJs (WebCrypto's `SubtleCrypto.digest` is async-only).
 *
 * Feed bytes with [update] (call repeatedly to hash `a ‖ b` without first concatenating),
 * then [digestInto] to write the 48-byte result into a caller-owned destination. Reading is
 * non-destructive. Not thread-safe — one instance per digest, not reusable after [digestInto].
 */
expect class Sha384Digest() {
    /** Absorbs the remaining bytes of [input] (non-destructive). Returns `this` for chaining. */
    fun update(input: ReadBuffer): Sha384Digest

    /**
     * Finalizes and writes [SHA384_DIGEST_BYTES] bytes into [dest] at its current
     * position, advancing it. [dest] must have at least [SHA384_DIGEST_BYTES] remaining.
     */
    fun digestInto(dest: WriteBuffer)
}

/**
 * One-shot SHA-384 of [input]'s remaining bytes, written into [dest] (zero allocation).
 *
 * [dest] must have at least [SHA384_DIGEST_BYTES] remaining.
 */
fun sha384(
    input: ReadBuffer,
    dest: WriteBuffer,
) {
    require(dest.remaining() >= SHA384_DIGEST_BYTES) {
        "dest needs $SHA384_DIGEST_BYTES bytes remaining, has ${dest.remaining()}"
    }
    Sha384Digest().update(input).digestInto(dest)
}

/**
 * One-shot SHA-384 of [input]'s remaining bytes, returning a freshly allocated,
 * read-ready buffer allocated via [factory].
 */
fun sha384(
    input: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    val out: PlatformBuffer = factory.allocate(SHA384_DIGEST_BYTES)
    Sha384Digest().update(input).digestInto(out)
    out.resetForRead()
    return out
}
