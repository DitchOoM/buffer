package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.use

/**
 * Pure-common HKDF-SHA256 (RFC 5869), built on the platform-native [HmacSha256Mac].
 *
 * Because the only primitive it needs is HMAC — which is already platform-native and
 * synchronous on every target — HKDF itself stays in `commonMain` and runs identically
 * everywhere. Used for deterministic nonce derivation and key-schedule expansion.
 *
 * All key-derived intermediates (the PRK and each `T` block) are allocated from a
 * secure, deterministic scratch factory and wiped on the way out — even on the failure
 * path. The output [WriteBuffer] is owned by the caller; pass a secure/deterministic
 * destination (see [secure]) if it too must be wiped.
 */
object Hkdf {
    private const val HASH_LEN = HMAC_SHA256_BYTES // 32
    private const val MAX_BLOCKS = 255

    /** Scratch factory for key-derived intermediates: deterministic so it can be `use {}`-freed, secure so it is wiped. */
    private val scratch: BufferFactory get() = BufferFactory.deterministic().secure()

    /**
     * HKDF-Extract: writes `PRK = HMAC(salt, ikm)` (32 bytes) into [dest].
     * A null/empty [salt] is treated as [HASH_LEN] zero bytes (per RFC 5869).
     */
    fun extractInto(
        salt: ReadBuffer?,
        ikm: ReadBuffer,
        dest: WriteBuffer,
    ) {
        if (salt != null && salt.remaining() > 0) {
            HmacSha256Mac(salt).update(ikm).doFinalInto(dest)
        } else {
            // Empty salt → a block of zero bytes. The scratch buffer is zero-initialized.
            scratch.allocate(HASH_LEN).use { zeroSalt ->
                HmacSha256Mac(zeroSalt).update(ikm).doFinalInto(dest)
            }
        }
    }

    /**
     * HKDF-Expand: writes [length] bytes of output keying material into [dest], derived
     * from the pseudo-random key [prk] (32 bytes) and optional [info].
     *
     * [dest] must have at least [length] bytes remaining.
     */
    fun expandInto(
        prk: ReadBuffer,
        info: ReadBuffer?,
        length: Int,
        dest: WriteBuffer,
    ) {
        require(length >= 0) { "length must be non-negative, was $length" }
        val blocks = (length + HASH_LEN - 1) / HASH_LEN
        require(blocks <= MAX_BLOCKS) { "HKDF cannot expand to $length bytes (max ${MAX_BLOCKS * HASH_LEN})" }
        require(dest.remaining() >= length) { "dest needs $length bytes remaining, has ${dest.remaining()}" }

        // Two ping-pong T blocks plus a one-byte counter, all wiped on close.
        scratch.allocate(HASH_LEN).use { tA ->
            scratch.allocate(HASH_LEN).use { tB ->
                scratch.allocate(1).use { counter ->
                    var prev: PlatformBuffer? = null // T(0) is empty
                    var cur = tA
                    var spare = tB
                    var written = 0
                    for (i in 1..blocks) {
                        // T(i) = HMAC(prk, T(i-1) ‖ info ‖ i) — streamed, never concatenated.
                        val mac = HmacSha256Mac(prk)
                        prev?.let { mac.update(it) }
                        info?.let { mac.update(it) }
                        counter.resetForWrite()
                        counter.writeByte(i.toByte())
                        counter.resetForRead()
                        mac.update(counter)
                        cur.resetForWrite()
                        mac.doFinalInto(cur)
                        cur.resetForRead()

                        val take = minOf(HASH_LEN, length - written)
                        for (j in 0 until take) dest.writeByte(cur.get(j))
                        written += take

                        // Swap: this block becomes T(i-1) for the next round.
                        prev = cur
                        val next = spare
                        spare = cur
                        cur = next
                    }
                }
            }
        }
    }

    /**
     * One-shot HKDF-SHA256: extract then expand to [length] bytes, written into [dest]
     * (which must have at least [length] bytes remaining).
     */
    fun deriveInto(
        salt: ReadBuffer?,
        ikm: ReadBuffer,
        info: ReadBuffer?,
        length: Int,
        dest: WriteBuffer,
    ) {
        scratch.allocate(HASH_LEN).use { prk ->
            extractInto(salt, ikm, prk)
            prk.resetForRead()
            expandInto(prk, info, length, dest)
        }
    }

    /**
     * One-shot HKDF-SHA256 returning a freshly allocated, read-ready buffer of [length]
     * bytes allocated via [factory]. Use `factory = BufferFactory.deterministic().secure()`
     * if the derived key material must be wiped after use.
     */
    fun derive(
        salt: ReadBuffer?,
        ikm: ReadBuffer,
        info: ReadBuffer?,
        length: Int,
        factory: BufferFactory,
    ): ReadBuffer {
        val out = factory.allocate(length)
        deriveInto(salt, ikm, info, length, out)
        out.resetForRead()
        return out
    }
}
