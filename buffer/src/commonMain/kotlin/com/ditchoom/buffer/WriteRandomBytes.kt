package com.ditchoom.buffer

import kotlin.random.Random

/**
 * Writes [count] uniformly-random bytes starting at this buffer's current
 * position, advancing position by [count]. Fails with
 * [BufferOverflowException] if fewer than [count] bytes remain.
 *
 * Intended to replace the common `ByteArray(n) { Random.nextInt().toByte() }`
 * + `writeBytes()` / `.toNSData()` pattern (WebSocket handshake keys,
 * WS-Masking IVs, CSRF nonces). Writes byte-by-byte in chunks of Long
 * (8 bytes per Random call) when possible, falling back to single bytes.
 *
 * Security note: backed by [Random.Default] which is not cryptographically
 * secure. RFC 6455 §4.1 explicitly states the WebSocket client masking
 * key should be "unpredictable from the server's perspective" but does
 * not require CSPRNG-grade randomness. Callers needing a CSPRNG should
 * supply [random] explicitly (e.g. `SecureRandom` on JVM).
 */
fun WriteBuffer.writeRandomBytes(
    count: Int,
    random: Random = Random.Default,
): WriteBuffer {
    require(count >= 0) { "count must be non-negative, was $count" }
    if (count == 0) return this
    checkWriteBounds(count)

    // Emit bytes 8 at a time via nextLong(); tail in a single-byte loop.
    val bulk = count and -8 // count rounded down to a multiple of 8
    var i = 0
    while (i < bulk) {
        val v = random.nextLong()
        writeByte((v ushr 0).toByte())
        writeByte((v ushr 8).toByte())
        writeByte((v ushr 16).toByte())
        writeByte((v ushr 24).toByte())
        writeByte((v ushr 32).toByte())
        writeByte((v ushr 40).toByte())
        writeByte((v ushr 48).toByte())
        writeByte((v ushr 56).toByte())
        i += 8
    }
    while (i < count) {
        writeByte(random.nextInt().toByte())
        i++
    }
    return this
}
