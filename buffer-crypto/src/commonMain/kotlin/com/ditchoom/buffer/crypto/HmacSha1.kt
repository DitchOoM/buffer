package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/** SHA-1 digest length in bytes (FIPS 180-4). */
const val SHA1_DIGEST_BYTES = 20

/** SHA-1 internal block length in bytes. */
const val SHA1_BLOCK_BYTES = 64

/** HMAC-SHA1 output length in bytes (equals the SHA-1 digest length). */
const val HMAC_SHA1_BYTES = SHA1_DIGEST_BYTES

/**
 * Incremental HMAC-SHA1 (RFC 2104).
 *
 * Backed by the platform's native MAC (JCA `Mac("HmacSHA1")` on JVM/Android, CommonCrypto
 * `CCHmac` on Apple, BoringSSL `HMAC_*` on Linux) and by a pure-Kotlin implementation on js/wasmJs.
 *
 * SHA-1 is retained here **only** where an interop obligation demands it — STUN/TURN
 * MESSAGE-INTEGRITY (RFC 8489/8656) and the legacy SRTP suite `SRTP_AES128_CM_SHA1_80`
 * (RFC 3711). It is not a general-purpose digest choice; new protocols should prefer
 * [HmacSha256Mac].
 *
 * The key is the remaining bytes of the [key] buffer passed to the constructor.
 * Feed the message with [update] (call repeatedly to MAC `a ‖ b` without concatenating),
 * then [doFinalInto] to write the 20-byte tag into a caller-owned destination.
 * Reads are non-destructive (operate on `slice()`s).
 *
 * Not thread-safe — use one instance per MAC. Key-bearing intermediates are wiped after use.
 * One-shot: [update] or [doFinalInto] after finalization throws [IllegalStateException].
 */
expect class HmacSha1Mac(
    key: ReadBuffer,
) : AutoCloseable {
    /** Absorbs the remaining bytes of [input] (non-destructive). Returns `this` for chaining. */
    fun update(input: ReadBuffer): HmacSha1Mac

    /**
     * Finalizes and writes [HMAC_SHA1_BYTES] bytes into [dest] at its current
     * position, advancing it. [dest] must have at least [HMAC_SHA1_BYTES] remaining.
     * Releases the MAC state; any further [update]/[doFinalInto] throws [IllegalStateException].
     */
    fun doFinalInto(dest: WriteBuffer)

    /**
     * Releases the (key-derived) MAC state without producing a tag — for a MAC abandoned before
     * [doFinalInto] (which releases the state itself). Idempotent; a no-op after [doFinalInto].
     */
    override fun close()
}

/**
 * One-shot HMAC-SHA1 of [message] under [key], written into [dest] (zero allocation).
 *
 * [dest] must have at least [HMAC_SHA1_BYTES] remaining.
 */
fun hmacSha1(
    key: ReadBuffer,
    message: ReadBuffer,
    dest: WriteBuffer,
) {
    require(dest.remaining() >= HMAC_SHA1_BYTES) {
        "dest needs $HMAC_SHA1_BYTES bytes remaining, has ${dest.remaining()}"
    }
    HmacSha1Mac(key).update(message).doFinalInto(dest)
}

/**
 * One-shot HMAC-SHA1 of [message] under [key], returning a freshly allocated,
 * read-ready buffer allocated via [factory].
 */
fun hmacSha1(
    key: ReadBuffer,
    message: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    val out: PlatformBuffer = factory.allocate(HMAC_SHA1_BYTES)
    HmacSha1Mac(key).update(message).doFinalInto(out)
    out.resetForRead()
    return out
}
