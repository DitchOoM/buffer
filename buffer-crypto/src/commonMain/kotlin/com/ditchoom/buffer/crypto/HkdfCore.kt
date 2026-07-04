package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.use

/**
 * HKDF-Expand's block-counter ceiling (RFC 5869 §2.3): output is at most 255 hash blocks, so the
 * maximum length is `255 * Nh`. Shared with the pre-allocation length checks (e.g. HPKE export).
 */
internal const val HKDF_MAX_BLOCKS = 255

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
    private val maxOutput = HKDF_MAX_BLOCKS * hashLen

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
        require(blocks <= HKDF_MAX_BLOCKS) { "HKDF cannot expand to $length bytes (max $maxOutput)" }
        require(dest.remaining() >= length) { "dest needs $length bytes remaining, has ${dest.remaining()}" }

        // Two ping-pong T blocks plus a one-byte counter, all wiped in `finally`.
        // A single try/finally (rather than three nested `use {}`) keeps the inlined
        // try/catch/finally nesting shallow: Kotlin/Native's body-lowering pass recurses
        // per inlined `use {}`, and three nested here overflowed its stack on iOS
        // (StackOverflowError in body lowering). Same secure-erase guarantee — all three
        // scratch buffers are freed on every exit path, including exceptions.
        //
        // The two blocks ping-pong by array index — `t[(i-1) and 1]` is T(i), `t[i and 1]`
        // is T(i-1). This deliberately avoids a `var prev/cur/spare` swap: reassigning locals
        // to each other in a loop builds a cyclic variable-alias graph that Kotlin/Native's
        // `CastsOptimization.buildNullablePredicate` walks WITHOUT a visited-set, so a nullable
        // `prev` fed from that cycle recurses forever → StackOverflowError in body lowering on
        // iOS (independent of -Xss). Indexed `val`s have no reassignment and no nullable, so
        // the predicate builder terminates.
        val t = arrayOf(scratch.allocate(hashLen), scratch.allocate(hashLen))
        val counter = scratch.allocate(1)
        try {
            var written = 0
            for (i in 1..blocks) {
                // T(i) = HMAC(prk, T(i-1) ‖ info ‖ i) — streamed, never concatenated.
                val cur = t[(i - 1) and 1]
                val mac = newMac(prk)
                // T(i-1): the other buffer, still at position 0 with limit == hashLen from
                // the previous round (a full block — only the final block is limit-clamped,
                // and it is never replayed). T(0) is empty, so skip it on the first round.
                if (i > 1) mac.update(t[i and 1])
                info?.let { mac.update(it) }
                counter.resetForWrite()
                counter.writeByte(i.toByte())
                counter.resetForRead()
                mac.update(counter)
                cur.resetForWrite()
                mac.doFinalInto(cur)
                cur.resetForRead()

                // Bulk-copy this block's contribution into dest. `slice()` is a
                // zero-copy view that does NOT advance `cur`'s position, so `cur`
                // stays at position 0 and can still be replayed as T(i-1) into the
                // next round's MAC. `setLimit(take)` clamps the final, possibly-partial
                // block; only the last block is partial and it is never reused, so
                // clamping the limit is safe.
                val take = minOf(hashLen, length - written)
                cur.setLimit(take)
                dest.write(cur.slice())
                written += take
            }
        } finally {
            counter.freeNativeMemory()
            t[1].freeNativeMemory()
            t[0].freeNativeMemory()
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
}
