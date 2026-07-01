package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/** HMAC-SHA256 output length in bytes (equals the SHA-256 digest length). */
const val HMAC_SHA256_BYTES = SHA256_DIGEST_BYTES

/**
 * Incremental HMAC-SHA256 (RFC 2104).
 *
 * Backed by the platform's native MAC (JCA `Mac("HmacSHA256")` on JVM/Android,
 * CommonCrypto `CCHmac` on Apple) and by a pure-Kotlin implementation on js/wasmJs.
 *
 * The key is the remaining bytes of the [key] buffer passed to the constructor.
 * Feed the message with [update] (call repeatedly to MAC `a ‖ b` without concatenating),
 * then [doFinalInto] to write the 32-byte tag into a caller-owned destination.
 * Reads are non-destructive (operate on `slice()`s).
 *
 * Not thread-safe — use one instance per MAC. Key-bearing intermediates are wiped after use.
 * One-shot: [update] or [doFinalInto] after finalization throws [IllegalStateException].
 */
expect class HmacSha256Mac(
    key: ReadBuffer,
) {
    /** Absorbs the remaining bytes of [input] (non-destructive). Returns `this` for chaining. */
    fun update(input: ReadBuffer): HmacSha256Mac

    /**
     * Finalizes and writes [HMAC_SHA256_BYTES] bytes into [dest] at its current
     * position, advancing it. [dest] must have at least [HMAC_SHA256_BYTES] remaining.
     */
    fun doFinalInto(dest: WriteBuffer)
}

/**
 * One-shot HMAC-SHA256 of [message] under [key], written into [dest] (zero allocation).
 *
 * [dest] must have at least [HMAC_SHA256_BYTES] remaining.
 */
fun hmacSha256(
    key: ReadBuffer,
    message: ReadBuffer,
    dest: WriteBuffer,
) {
    require(dest.remaining() >= HMAC_SHA256_BYTES) {
        "dest needs $HMAC_SHA256_BYTES bytes remaining, has ${dest.remaining()}"
    }
    HmacSha256Mac(key).update(message).doFinalInto(dest)
}

/**
 * One-shot HMAC-SHA256 of [message] under [key], returning a freshly allocated,
 * read-ready buffer allocated via [factory].
 */
fun hmacSha256(
    key: ReadBuffer,
    message: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    val out: PlatformBuffer = factory.allocate(HMAC_SHA256_BYTES)
    HmacSha256Mac(key).update(message).doFinalInto(out)
    out.resetForRead()
    return out
}
