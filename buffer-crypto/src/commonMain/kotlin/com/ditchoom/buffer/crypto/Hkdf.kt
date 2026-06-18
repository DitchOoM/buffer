package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * HKDF-SHA256 (RFC 5869), built on the platform-native [HmacSha256Mac] via the common
 * [HkdfEngine]. Runs identically on every target. Used for deterministic nonce derivation
 * and key-schedule expansion. Key-derived intermediates are wiped on the way out.
 */
object Hkdf {
    private val engine = HkdfEngine(HMAC_SHA256_BYTES, ::sha256HkdfMac)

    /** HKDF-Extract: writes `PRK = HMAC(salt, ikm)` (32 bytes) into [dest]. Null/empty salt ⇒ zero block. */
    fun extractInto(
        salt: ReadBuffer?,
        ikm: ReadBuffer,
        dest: WriteBuffer,
    ) = engine.extractInto(salt, ikm, dest)

    /** HKDF-Expand: writes [length] bytes of OKM into [dest] from [prk] (32 bytes) and optional [info]. */
    fun expandInto(
        prk: ReadBuffer,
        info: ReadBuffer?,
        length: Int,
        dest: WriteBuffer,
    ) = engine.expandInto(prk, info, length, dest)

    /** One-shot HKDF-SHA256: extract then expand to [length] bytes, written into [dest]. */
    fun deriveInto(
        salt: ReadBuffer?,
        ikm: ReadBuffer,
        info: ReadBuffer?,
        length: Int,
        dest: WriteBuffer,
    ) = engine.deriveInto(salt, ikm, info, length, dest)

    /**
     * One-shot HKDF-SHA256 returning a freshly allocated, read-ready buffer of [length] bytes
     * from [factory]. Use `factory = BufferFactory.deterministic().secure()` if the derived
     * key material must be wiped after use.
     */
    fun derive(
        salt: ReadBuffer?,
        ikm: ReadBuffer,
        info: ReadBuffer?,
        length: Int,
        factory: BufferFactory,
    ): ReadBuffer = engine.derive(salt, ikm, info, length, factory)
}

private fun sha256HkdfMac(key: ReadBuffer): HkdfMac {
    val mac = HmacSha256Mac(key)
    return object : HkdfMac {
        override fun update(input: ReadBuffer) {
            mac.update(input)
        }

        override fun doFinalInto(dest: WriteBuffer) {
            mac.doFinalInto(dest)
        }
    }
}
