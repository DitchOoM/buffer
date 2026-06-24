package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.use

/**
 * The minimal streaming-MAC surface [HkdfEngine] needs. Adapts any of the platform-native
 * `Hmac*Mac` classes without forcing them to share a supertype (keeps the landed expect
 * classes untouched).
 */
internal interface HkdfMac {
    fun update(input: ReadBuffer)

    fun doFinalInto(dest: WriteBuffer)
}

/**
 * Pure-common HKDF (RFC 5869) over any HMAC, parameterized by the underlying hash's output
 * length and a MAC constructor. Because HMAC is already platform-native and synchronous on
 * every target, HKDF itself stays in `commonMain` and runs identically everywhere.
 *
 * All key-derived intermediates (the PRK and each `T` block) are allocated from a secure,
 * deterministic scratch factory and wiped on the way out — even on the failure path. The
 * output [WriteBuffer] is owned by the caller; pass a secure/deterministic destination (see
 * [secure]) if it too must be wiped.
 */
internal class HkdfEngine(
    private val hashLen: Int,
    private val newMac: (key: ReadBuffer) -> HkdfMac,
) {
    private val maxOutput = MAX_BLOCKS * hashLen

    /**
     * Scratch factory for key-derived intermediates: deterministic so it can be `use {}`-freed,
     * secure so it is wiped.
     */
    private val scratch: BufferFactory get() = BufferFactory.deterministic().secure()

    /**
     * HKDF-Extract: writes `PRK = HMAC(salt, ikm)` ([hashLen] bytes) into [dest].
     * A null/empty [salt] is treated as [hashLen] zero bytes (per RFC 5869).
     */
    fun extractInto(
        salt: ReadBuffer?,
        ikm: ReadBuffer,
        dest: WriteBuffer,
    ) {
        if (salt != null && salt.remaining() > 0) {
            newMac(salt).also { it.update(ikm) }.doFinalInto(dest)
        } else {
            // Empty salt → a block of zero bytes (RFC 5869). The secure [scratch] factory
            // zero-initializes on allocate (see SecureBufferFactory), so this is guaranteed
            // zero on every platform — not a reliance on the underlying allocator.
            scratch.allocate(hashLen).use { zeroSalt ->
                newMac(zeroSalt).also { it.update(ikm) }.doFinalInto(dest)
            }
        }
    }

    /**
     * HKDF-Expand: writes [length] bytes of output keying material into [dest], derived from
     * the pseudo-random key [prk] ([hashLen] bytes) and optional [info].
     */
    fun expandInto(
        prk: ReadBuffer,
        info: ReadBuffer?,
        length: Int,
        dest: WriteBuffer,
    ) {
        require(length >= 0) { "length must be non-negative, was $length" }
        val blocks = (length + hashLen - 1) / hashLen
        require(blocks <= MAX_BLOCKS) { "HKDF cannot expand to $length bytes (max $maxOutput)" }
        require(dest.remaining() >= length) { "dest needs $length bytes remaining, has ${dest.remaining()}" }

        // Two ping-pong T blocks plus a one-byte counter, all wiped on close.
        scratch.allocate(hashLen).use { tA ->
            scratch.allocate(hashLen).use { tB ->
                scratch.allocate(1).use { counter ->
                    var prev: PlatformBuffer? = null // T(0) is empty
                    var cur = tA
                    var spare = tB
                    var written = 0
                    for (i in 1..blocks) {
                        // T(i) = HMAC(prk, T(i-1) ‖ info ‖ i) — streamed, never concatenated.
                        val mac = newMac(prk)
                        prev?.let { mac.update(it) }
                        info?.let { mac.update(it) }
                        counter.resetForWrite()
                        counter.writeByte(i.toByte())
                        counter.resetForRead()
                        mac.update(counter)
                        cur.resetForWrite()
                        mac.doFinalInto(cur)
                        cur.resetForRead()

                        val take = minOf(hashLen, length - written)
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

    /** One-shot: extract then expand to [length] bytes, written into [dest]. */
    fun deriveInto(
        salt: ReadBuffer?,
        ikm: ReadBuffer,
        info: ReadBuffer?,
        length: Int,
        dest: WriteBuffer,
    ) {
        scratch.allocate(hashLen).use { prk ->
            extractInto(salt, ikm, prk)
            prk.resetForRead()
            expandInto(prk, info, length, dest)
        }
    }

    /** One-shot returning a freshly allocated, read-ready buffer of [length] bytes from [factory]. */
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

    private companion object {
        const val MAX_BLOCKS = 255
    }
}
