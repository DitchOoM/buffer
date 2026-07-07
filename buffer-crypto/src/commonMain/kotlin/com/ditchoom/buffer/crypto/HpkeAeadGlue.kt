package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

/*
 * Bridges the HPKE per-message AEAD ops onto the landed AEAD primitive family, keyed by the
 * already-derived HPKE key (wrapped once per context via [hpkeAeadKeyOf]) and the per-message
 * explicit nonce.
 *
 * - AES-GCM uses the explicit-nonce async seam (aesGcmSealWithNonceAsync / aesGcmOpenWithNonceAsync),
 *   which works on every platform including the web (WebCrypto).
 * - ChaCha20-Poly1305 uses the synchronous native primitives (chaChaPolySeal / chaChaPolyOpen); it is
 *   unavailable on the web and that suite is gated false by the AEAD capability witness.
 *
 * Both seal/open take `ct || tag` (no nonce prefix) — HPKE never prepends the nonce, it is derived
 * from the context's base nonce and sequence number.
 */

/**
 * Wraps the derived HPKE AEAD key (`HpkeAead.nk` bytes) as a typed AEAD key. Created once per
 * context at key-schedule time; the wrapper holds its own secure-scratch copy of the material,
 * which [closeMaterial] (driven by [HpkeContext.close]) zeroes.
 */
internal fun hpkeAeadKeyOf(
    aead: HpkeAead,
    key: ReadBuffer,
): AeadKey =
    when (aead) {
        HpkeAead.Aes128Gcm, HpkeAead.Aes256Gcm -> AesGcmKey.of(viewRemaining(key), secureScratch)
        HpkeAead.ChaCha20Poly1305 -> ChaChaPolyKey.of(viewRemaining(key), secureScratch)
    }

/** Zeroes and frees the wrapper's copied key material (each AEAD key family is closeable). */
internal fun AeadKey.closeMaterial() {
    when (this) {
        is AesGcmKey -> close()
        is ChaChaPolyKey -> close()
    }
}

/** Seals [plaintext] under the context's wrapped AEAD [key] and explicit [nonce]. */
internal suspend fun hpkeAeadSeal(
    key: AeadKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    factory: BufferFactory,
): PlatformBuffer =
    when (key) {
        is AesGcmKey -> aesGcmSealWithNonceAsync(key, nonce, aad, plaintext, factory)
        is ChaChaPolyKey -> {
            if (!chaChaPolyReachable) {
                throw UnsupportedOperationException("ChaCha20-Poly1305 is not supported on this platform")
            }
            val dest = factory.allocate(plaintext.remaining() + AEAD_TAG_BYTES)
            chaChaPolySeal(key, nonce, aad, plaintext, dest)
            dest.resetForRead()
            dest
        }
    }

/** Opens a `ct || tag` blob under the context's wrapped AEAD [key] and explicit [nonce]. */
internal suspend fun hpkeAeadOpen(
    key: AeadKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    factory: BufferFactory,
): PlatformBuffer =
    when (key) {
        is AesGcmKey -> aesGcmOpenWithNonceAsync(key, nonce, aad, ciphertextAndTag, factory)
        is ChaChaPolyKey -> {
            if (!chaChaPolyReachable) {
                throw UnsupportedOperationException("ChaCha20-Poly1305 is not supported on this platform")
            }
            require(ciphertextAndTag.remaining() >= AEAD_TAG_BYTES) {
                "ciphertext too short: need at least $AEAD_TAG_BYTES bytes"
            }
            val dest = factory.allocate(ciphertextAndTag.remaining() - AEAD_TAG_BYTES)
            chaChaPolyOpen(key, nonce, aad, ciphertextAndTag, dest)
            dest.resetForRead()
            dest
        }
    }

/** A read-ready slice over [buffer]'s remaining bytes (non-destructive, shares storage). */
private fun viewRemaining(buffer: ReadBuffer): ReadBuffer = buffer.slice()
