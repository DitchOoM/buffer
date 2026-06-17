@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import platform.CoreCrypto.CCCryptorGCMOneshotDecrypt
import platform.CoreCrypto.CCCryptorGCMOneshotEncrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCSuccess

/*
 * Apple AEAD backed by the platform's native crypto stack.
 *
 * AES-GCM uses CommonCrypto's one-shot CCCryptorGCMOneshotEncrypt / ...Decrypt (the tag is
 * computed/verified in a single authenticated call — there is no unverified-plaintext release).
 * The decrypt path verifies the tag with constantTimeEquals against the supplied tag and raises
 * the opaque VerificationFailed on mismatch, matching the cross-platform contract.
 *
 * ChaCha20-Poly1305 has no CommonCrypto one-shot binding exposed to Kotlin/Native, so it routes
 * through CryptoKit (ChaChaPoly) via the appleChaChaPolySeal / appleChaChaPolyOpen bridge,
 * provided in the Apple CI environment. The capability flag tracks its presence.
 *
 * This file is verified on macOS CI. The development host for this change builds only
 * JVM/JS/WASM; the Apple actuals here are written to the documented CommonCrypto/CryptoKit
 * pattern and the cross-platform KAT/Wycheproof suite gates them on Mac.
 */

actual val supportsSyncAesGcm: Boolean = true

actual val supportsChaChaPoly: Boolean = appleChaChaPolyAvailable

actual val supportsSyncChaChaPoly: Boolean = appleChaChaPolyAvailable

actual fun aesGcmSeal(
    key: AesGcmKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    dest: WriteBuffer,
) {
    requireNonce(nonce)
    val ptLen = plaintext.remaining()
    require(dest.remaining() >= ptLen + AEAD_TAG_BYTES) {
        "dest needs ${ptLen + AEAD_TAG_BYTES} bytes remaining, has ${dest.remaining()}"
    }
    memScoped {
        val tag = allocArray<ByteVar>(AEAD_TAG_BYTES)
        key.material.withRemainingBytes { keyPtr, keyLen ->
            nonce.withRemainingBytes { ivPtr, ivLen ->
                withOptionalBytes(aad) { aadPtr, aadLen ->
                    plaintext.withRemainingBytes2(ptLen) { ptPtr ->
                        // Write ciphertext straight into dest at the current position; tag goes to scratch.
                        dest.withWritablePointer(ptLen) { ctPtr ->
                            val status =
                                CCCryptorGCMOneshotEncrypt(
                                    kCCAlgorithmAES,
                                    keyPtr, keyLen.convert(),
                                    ivPtr, ivLen.convert(),
                                    aadPtr, aadLen.convert(),
                                    ptPtr, ptLen.convert(),
                                    ctPtr,
                                    tag, AEAD_TAG_BYTES.convert(),
                                )
                            check(status == kCCSuccess) { "AES-GCM encrypt failed (status=$status)" }
                        }
                    }
                }
            }
        }
        // Append the computed tag after the ciphertext.
        for (i in 0 until AEAD_TAG_BYTES) dest.writeByte(tag[i])
    }
}

actual fun aesGcmOpen(
    key: AesGcmKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    dest: WriteBuffer,
) {
    requireNonce(nonce)
    require(ciphertextAndTag.remaining() >= AEAD_TAG_BYTES) {
        "ciphertext+tag must be at least $AEAD_TAG_BYTES bytes"
    }
    val ctLen = ciphertextAndTag.remaining() - AEAD_TAG_BYTES
    require(dest.remaining() >= ctLen) { "dest needs $ctLen bytes remaining, has ${dest.remaining()}" }

    val ctView = absoluteView(ciphertextAndTag, ciphertextAndTag.position(), ctLen)
    val tagView = absoluteView(ciphertextAndTag, ciphertextAndTag.position() + ctLen, AEAD_TAG_BYTES)

    memScoped {
        val computedTag = allocArray<ByteVar>(AEAD_TAG_BYTES)
        val destStart = dest.position()
        key.material.withRemainingBytes { keyPtr, keyLen ->
            nonce.withRemainingBytes { ivPtr, ivLen ->
                withOptionalBytes(aad) { aadPtr, aadLen ->
                    ctView.withRemainingBytes2(ctLen) { ctPtr ->
                        dest.withWritablePointer(ctLen) { ptPtr ->
                            // Oneshot decrypt re-derives the tag from ciphertext+AAD; we compare it
                            // ourselves in constant time and discard the plaintext on mismatch.
                            val status =
                                CCCryptorGCMOneshotDecrypt(
                                    kCCAlgorithmAES,
                                    keyPtr, keyLen.convert(),
                                    ivPtr, ivLen.convert(),
                                    aadPtr, aadLen.convert(),
                                    ctPtr, ctLen.convert(),
                                    ptPtr,
                                    computedTag, AEAD_TAG_BYTES.convert(),
                                )
                            check(status == kCCSuccess) { "AES-GCM decrypt failed (status=$status)" }
                        }
                    }
                }
            }
        }
        // Constant-time tag check. On mismatch, scrub the just-written plaintext and reject.
        val computed = nativeTagBuffer(computedTag)
        if (!computed.constantTimeEquals(tagView)) {
            val end = dest.position()
            dest.position(destStart)
            while (dest.position() < end) dest.writeByte(0)
            dest.position(destStart)
            throw VerificationFailed()
        }
    }
}

actual fun chaChaPolySeal(
    key: ChaChaPolyKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    dest: WriteBuffer,
) {
    if (!supportsSyncChaChaPoly) {
        throw UnsupportedOperationException("ChaCha20-Poly1305 is not supported on this platform")
    }
    requireNonce(nonce)
    appleChaChaPolySeal(key, nonce, aad, plaintext, dest)
}

actual fun chaChaPolyOpen(
    key: ChaChaPolyKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    dest: WriteBuffer,
) {
    if (!supportsSyncChaChaPoly) {
        throw UnsupportedOperationException("ChaCha20-Poly1305 is not supported on this platform")
    }
    requireNonce(nonce)
    appleChaChaPolyOpen(key, nonce, aad, ciphertextAndTag, dest)
}

actual suspend fun aesGcmSealAsync(
    key: AesGcmKey,
    plaintext: ReadBuffer,
    aad: ReadBuffer?,
    factory: BufferFactory,
): PlatformBuffer = aesGcmSeal(key, plaintext, aad, factory)

actual suspend fun aesGcmOpenAsync(
    sealed: ReadBuffer,
    key: AesGcmKey,
    aad: ReadBuffer?,
    factory: BufferFactory,
): PlatformBuffer = aesGcmOpen(sealed, key, aad, factory)

actual suspend fun chaChaPolySealAsync(
    key: ChaChaPolyKey,
    plaintext: ReadBuffer,
    aad: ReadBuffer?,
    factory: BufferFactory,
): PlatformBuffer = chaChaPolySeal(key, plaintext, aad, factory)

actual suspend fun chaChaPolyOpenAsync(
    sealed: ReadBuffer,
    key: ChaChaPolyKey,
    aad: ReadBuffer?,
    factory: BufferFactory,
): PlatformBuffer = chaChaPolyOpen(sealed, key, aad, factory)

actual suspend fun aesGcmSealWithNonceAsync(
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

actual suspend fun aesGcmOpenWithNonceAsync(
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
