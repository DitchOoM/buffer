package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.WriteBuffer

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
