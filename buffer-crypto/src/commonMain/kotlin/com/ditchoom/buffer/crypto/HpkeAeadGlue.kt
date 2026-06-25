package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

/*
 * Bridges the HPKE per-message AEAD ops onto the landed AEAD primitive family, keyed by the
 * already-derived HPKE key (HpkeAead.nk bytes) and the per-message explicit nonce.
 *
 * - AES-GCM uses the explicit-nonce async seam (aesGcmSealWithNonceAsync / aesGcmOpenWithNonceAsync),
 *   which works on every platform including the web (WebCrypto).
 * - ChaCha20-Poly1305 uses the synchronous native primitives (chaChaPolySeal / chaChaPolyOpen); it is
 *   unavailable on the web and that suite is gated false by the AEAD capability witness.
 *
 * Both seal/open take `ct || tag` (no nonce prefix) — HPKE never prepends the nonce, it is derived
 * from the context's base nonce and sequence number.
 */

/** Seals [plaintext] under the HPKE AEAD [aead], [key] (the derived AEAD key), and explicit [nonce]. */
internal suspend fun hpkeAeadSeal(
    aead: HpkeAead,
    key: ReadBuffer,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    factory: BufferFactory,
): PlatformBuffer =
    when (aead) {
        HpkeAead.Aes128Gcm, HpkeAead.Aes256Gcm -> {
            val gcmKey = AesGcmKey.of(viewRemaining(key), secureScratch)
            aesGcmSealWithNonceAsync(gcmKey, nonce, aad, plaintext, factory)
        }
        HpkeAead.ChaCha20Poly1305 -> {
            if (!chaChaPolyReachable) {
                throw UnsupportedOperationException("ChaCha20-Poly1305 is not supported on this platform")
            }
            val ccKey = ChaChaPolyKey.of(viewRemaining(key), secureScratch)
            val dest = factory.allocate(plaintext.remaining() + AEAD_TAG_BYTES)
            chaChaPolySeal(ccKey, nonce, aad, plaintext, dest)
            dest.resetForRead()
            dest
        }
    }

/** Opens a `ct || tag` blob under the HPKE AEAD [aead], [key], and explicit [nonce]. */
internal suspend fun hpkeAeadOpen(
    aead: HpkeAead,
    key: ReadBuffer,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    factory: BufferFactory,
): PlatformBuffer =
    when (aead) {
        HpkeAead.Aes128Gcm, HpkeAead.Aes256Gcm -> {
            val gcmKey = AesGcmKey.of(viewRemaining(key), secureScratch)
            aesGcmOpenWithNonceAsync(gcmKey, nonce, aad, ciphertextAndTag, factory)
        }
        HpkeAead.ChaCha20Poly1305 -> {
            if (!chaChaPolyReachable) {
                throw UnsupportedOperationException("ChaCha20-Poly1305 is not supported on this platform")
            }
            val ccKey = ChaChaPolyKey.of(viewRemaining(key), secureScratch)
            require(ciphertextAndTag.remaining() >= AEAD_TAG_BYTES) {
                "ciphertext too short: need at least $AEAD_TAG_BYTES bytes"
            }
            val dest = factory.allocate(ciphertextAndTag.remaining() - AEAD_TAG_BYTES)
            chaChaPolyOpen(ccKey, nonce, aad, ciphertextAndTag, dest)
            dest.resetForRead()
            dest
        }
    }

/** A read-ready slice over [buffer]'s remaining bytes (non-destructive, shares storage). */
private fun viewRemaining(buffer: ReadBuffer): ReadBuffer = buffer.slice()
