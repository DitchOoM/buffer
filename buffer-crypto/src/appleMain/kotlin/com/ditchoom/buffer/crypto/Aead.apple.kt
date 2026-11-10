@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import kotlinx.cinterop.ExperimentalForeignApi

/*
 * Apple AEAD on CryptoKit, through the Swift shim (`CryptoKitShim.swift`, `cryptokitshim` cinterop).
 *
 * AES-GCM is `AES.GCM.seal` / `AES.GCM.open` ([appleAesGcmSeal] / [appleAesGcmOpen]) and
 * ChaCha20-Poly1305 is `ChaChaPoly.seal` / `ChaChaPoly.open` ([appleChaChaPolySeal] /
 * [appleChaChaPolyOpen]). CryptoKit verifies the tag before it returns any plaintext, and the
 * bridge raises [VerificationFailed] with `dest` back at the position it was given.
 *
 * CommonCrypto is not used for AEAD: its GCM entry points are SPI (`CommonCryptorSPI.h`), and App
 * Store Connect rejects a binary that references them. `verifyNoPrivateAppleSymbols` fails the
 * build if one is linked.
 */

/** AES-GCM is synchronous via CryptoKit. */
actual val CryptoCapabilities.aesGcm: Aead<AesGcmKey, SyncCapableAesGcmKey> get() = Aead.Blocking(AesGcmBlockingOps)

/** ChaCha20-Poly1305 is synchronous via CryptoKit when the bridge is present. */
actual val CryptoCapabilities.chaChaPoly: OptionalAead<ChaChaPolyKey>
    get() =
        if (APPLE_CHACHA_POLY_AVAILABLE) {
            OptionalAead.Blocking(ChaChaPolyBlockingOps)
        } else {
            OptionalAead.Unavailable
        }

internal actual fun aesGcmSeal(
    key: AesGcmKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    dest: WriteBuffer,
) {
    requireNonce(nonce)
    appleAesGcmSeal(key, nonce, aad, plaintext, dest)
}

internal actual fun aesGcmOpen(
    key: AesGcmKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    dest: WriteBuffer,
) {
    requireNonce(nonce)
    appleAesGcmOpen(key, nonce, aad, ciphertextAndTag, dest)
}

internal actual fun chaChaPolySeal(
    key: ChaChaPolyKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    dest: WriteBuffer,
) {
    if (!APPLE_CHACHA_POLY_AVAILABLE) {
        throw UnsupportedOperationException("ChaCha20-Poly1305 is not supported on this platform")
    }
    requireNonce(nonce)
    appleChaChaPolySeal(key, nonce, aad, plaintext, dest)
}

internal actual fun chaChaPolyOpen(
    key: ChaChaPolyKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    dest: WriteBuffer,
) {
    if (!APPLE_CHACHA_POLY_AVAILABLE) {
        throw UnsupportedOperationException("ChaCha20-Poly1305 is not supported on this platform")
    }
    requireNonce(nonce)
    appleChaChaPolyOpen(key, nonce, aad, ciphertextAndTag, dest)
}

internal actual suspend fun aesGcmSealWithNonceAsync(
    key: AesGcmKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    factory: BufferFactory,
): PlatformBuffer {
    val out = factory.allocate(plaintext.remaining() + AEAD_TAG_BYTES)
    aesGcmSeal(key, nonce, aad, plaintext, out)
    out.resetForRead()
    return out
}

internal actual suspend fun aesGcmOpenWithNonceAsync(
    key: AesGcmKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    factory: BufferFactory,
): PlatformBuffer {
    val out = factory.allocate(maxOf(0, ciphertextAndTag.remaining() - AEAD_TAG_BYTES))
    aesGcmOpen(key, nonce, aad, ciphertextAndTag, out)
    out.resetForRead()
    return out
}

// =============================================================================
// Apple glue
// =============================================================================

private fun requireNonce(nonce: ReadBuffer) {
    require(nonce.remaining() == AEAD_NONCE_BYTES) {
        "nonce must be $AEAD_NONCE_BYTES bytes, was ${nonce.remaining()}"
    }
}
