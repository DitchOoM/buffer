package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * HKDF-SHA384 (RFC 5869), built on the platform-native [HmacSha384Mac] via the common
 * [HkdfEngine]. Key-derived intermediates are wiped on the way out.
 */
object HkdfSha384 {
    private val engine = HkdfEngine(HMAC_SHA384_BYTES, ::sha384HkdfMac)

    /** HKDF-Extract: writes `PRK = HMAC(salt, ikm)` (48 bytes) into [dest]. [Salt.None]/empty ⇒ zero block. */
    fun extractInto(
        salt: Salt,
        ikm: ReadBuffer,
        dest: WriteBuffer,
    ) = engine.extractInto(salt.bytesOrNull, ikm, dest)

    /** HKDF-Expand: writes [length] bytes of OKM into [dest] from [prk] (48 bytes) and optional [info]. */
    fun expandInto(
        prk: ReadBuffer,
        info: Info,
        length: Int,
        dest: WriteBuffer,
    ) = engine.expandInto(prk, info.bytesOrNull, length, dest)

    /** One-shot HKDF-SHA384: extract then expand to [length] bytes, written into [dest]. */
    fun deriveInto(
        salt: Salt,
        ikm: ReadBuffer,
        info: Info,
        length: Int,
        dest: WriteBuffer,
    ) = engine.deriveInto(salt.bytesOrNull, ikm, info.bytesOrNull, length, dest)

    /** One-shot HKDF-SHA384 returning a freshly allocated, read-ready buffer of [length] bytes from [factory]. */
    fun derive(
        salt: Salt,
        ikm: ReadBuffer,
        info: Info,
        length: Int,
        factory: BufferFactory,
    ): ReadBuffer = engine.derive(salt.bytesOrNull, ikm, info.bytesOrNull, length, factory)
}

private fun sha384HkdfMac(key: ReadBuffer): HkdfMac {
    val mac = HmacSha384Mac(key)
    return object : HkdfMac {
        override fun update(input: ReadBuffer) {
            mac.update(input)
        }

        override fun doFinalInto(dest: WriteBuffer) {
            mac.doFinalInto(dest)
        }
    }
}
