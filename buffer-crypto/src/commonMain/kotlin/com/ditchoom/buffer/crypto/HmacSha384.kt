package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/** HMAC-SHA384 output length in bytes (equals the SHA-384 digest length). */
const val HMAC_SHA384_BYTES = SHA384_DIGEST_BYTES

/**
 * Incremental HMAC-SHA384 (RFC 2104).
 *
 * Backed by the platform's native MAC (JCA `Mac("HmacSHA384")` on JVM/Android,
 * CommonCrypto `CCHmac` with `kCCHmacAlgSHA384` on Apple) and by a pure-Kotlin
 * implementation on js/wasmJs.
 *
 * The key is the remaining bytes of the [key] buffer. Feed the message with [update]
 * (call repeatedly to MAC `a ‖ b` without concatenating), then [doFinalInto] to write the
 * 48-byte tag into a caller-owned destination. Reads are non-destructive.
 *
 * Not thread-safe — one instance per MAC. Key-bearing intermediates are wiped after use.
 */
expect class HmacSha384Mac(
    key: ReadBuffer,
) {
    /** Absorbs the remaining bytes of [input] (non-destructive). Returns `this` for chaining. */
    fun update(input: ReadBuffer): HmacSha384Mac

    /**
     * Finalizes and writes [HMAC_SHA384_BYTES] bytes into [dest] at its current
     * position, advancing it. [dest] must have at least [HMAC_SHA384_BYTES] remaining.
     */
    fun doFinalInto(dest: WriteBuffer)
}

/**
 * One-shot HMAC-SHA384 of [message] under [key], written into [dest] (zero allocation).
 *
 * [dest] must have at least [HMAC_SHA384_BYTES] remaining.
 */
fun hmacSha384(
    key: ReadBuffer,
    message: ReadBuffer,
    dest: WriteBuffer,
) {
    require(dest.remaining() >= HMAC_SHA384_BYTES) {
        "dest needs $HMAC_SHA384_BYTES bytes remaining, has ${dest.remaining()}"
    }
    HmacSha384Mac(key).update(message).doFinalInto(dest)
}

/**
 * One-shot HMAC-SHA384 of [message] under [key], returning a freshly allocated,
 * read-ready buffer allocated via [factory].
 */
fun hmacSha384(
    key: ReadBuffer,
    message: ReadBuffer,
    factory: BufferFactory = BufferFactory.Default,
): ReadBuffer {
    val out: PlatformBuffer = factory.allocate(HMAC_SHA384_BYTES)
    HmacSha384Mac(key).update(message).doFinalInto(out)
    out.resetForRead()
    return out
}
