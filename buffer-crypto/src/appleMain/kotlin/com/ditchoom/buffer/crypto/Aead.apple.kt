@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.crypto.cinterop.CCCryptorGCMAddIV
import com.ditchoom.buffer.crypto.cinterop.CCCryptorGCMDecrypt
import com.ditchoom.buffer.crypto.cinterop.CCCryptorGCMEncrypt
import com.ditchoom.buffer.crypto.cinterop.CCCryptorGCMFinal
import com.ditchoom.buffer.crypto.cinterop.CCCryptorGCMaddAAD
import com.ditchoom.buffer.crypto.cinterop.kCCModeGCM
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCryptorCreateWithMode
import platform.CoreCrypto.CCCryptorRefVar
import platform.CoreCrypto.CCCryptorRelease
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCSuccess
import platform.posix.size_tVar

/*
 * Apple AEAD backed by the platform's native crypto stack.
 *
 * AES-GCM uses CommonCrypto's streaming GCM API (CCCryptorCreateWithMode(kCCModeGCM) →
 * CCCryptorGCMAddIV → CCCryptorGCMaddAAD → CCCryptorGCMEncrypt/Decrypt → CCCryptorGCMFinal),
 * bound through the `commoncryptogcm` cinterop because those entry points live in CommonCrypto's
 * SPI header and are absent from Kotlin/Native's platform.CoreCrypto binding. CCCryptorGCMFinal
 * outputs the recomputed tag for both directions; the decrypt path compares it against the
 * supplied tag with constantTimeEquals, scrubs the plaintext, and raises the opaque
 * VerificationFailed on mismatch before returning — no unverified-plaintext release.
 *
 * ChaCha20-Poly1305 has no CommonCrypto one-shot binding exposed to Kotlin/Native, so it routes
 * through CryptoKit (ChaChaPoly) via the appleChaChaPolySeal / appleChaChaPolyOpen bridge,
 * provided in the Apple CI environment. The capability flag tracks its presence.
 *
 * This file is verified on macOS CI. The development host for this change builds only
 * JVM/JS/WASM; the Apple actuals here are written to the documented CommonCrypto/CryptoKit
 * pattern and the cross-platform KAT/Wycheproof suite gates them on Mac.
 */

/** AES-GCM is synchronous via CommonCrypto's streaming GCM API. */
actual val CryptoCapabilities.aesGcm: Aead<AesGcmKey> get() = Aead.Blocking(AesGcmBlockingOps)

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
    val ptLen = plaintext.remaining()
    require(dest.remaining() >= ptLen + AEAD_TAG_BYTES) {
        "dest needs ${ptLen + AEAD_TAG_BYTES} bytes remaining, has ${dest.remaining()}"
    }
    memScoped {
        val tag = allocArray<ByteVar>(AEAD_TAG_BYTES)
        key.requireInMemoryMaterial().withRemainingBytes { keyPtr, keyLen ->
            nonce.withRemainingBytes { ivPtr, ivLen ->
                withOptionalBytes(aad) { aadPtr, aadLen ->
                    plaintext.withRemainingBytes2(ptLen) { ptPtr ->
                        // Write ciphertext straight into dest at the current position; tag goes to scratch.
                        dest.withWritablePointer(ptLen) { ctPtr ->
                            gcmCrypt(
                                encrypt = true,
                                keyPtr = keyPtr,
                                keyLen = keyLen,
                                ivPtr = ivPtr,
                                ivLen = ivLen,
                                aadPtr = aadPtr,
                                aadLen = aadLen,
                                dataIn = ptPtr,
                                dataLen = ptLen,
                                dataOut = ctPtr,
                                tagOut = tag,
                            )
                        }
                    }
                }
            }
        }
        // Append the computed tag after the ciphertext.
        for (i in 0 until AEAD_TAG_BYTES) dest.writeByte(tag[i])
    }
}

internal actual fun aesGcmOpen(
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
        key.requireInMemoryMaterial().withRemainingBytes { keyPtr, keyLen ->
            nonce.withRemainingBytes { ivPtr, ivLen ->
                withOptionalBytes(aad) { aadPtr, aadLen ->
                    ctView.withRemainingBytes2(ctLen) { ctPtr ->
                        dest.withWritablePointer(ctLen) { ptPtr ->
                            // GCMFinal re-derives the tag from ciphertext+AAD into computedTag; we
                            // compare it ourselves in constant time and discard the plaintext on
                            // mismatch (the streaming decrypt itself performs no authentication).
                            gcmCrypt(
                                encrypt = false,
                                keyPtr = keyPtr,
                                keyLen = keyLen,
                                ivPtr = ivPtr,
                                ivLen = ivLen,
                                aadPtr = aadPtr,
                                aadLen = aadLen,
                                dataIn = ctPtr,
                                dataLen = ctLen,
                                dataOut = ptPtr,
                                tagOut = computedTag,
                            )
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

/**
 * Runs one AES-GCM operation through CommonCrypto's streaming GCM state machine:
 * create → set IV → add AAD → encrypt/decrypt → finalize (writes the 16-byte tag to [tagOut]) →
 * release. The cryptor is always released. Throws on any non-success CommonCrypto status; the
 * decrypt path does not authenticate here — callers compare [tagOut] against the supplied tag in
 * constant time and scrub on mismatch.
 */
private fun gcmCrypt(
    encrypt: Boolean,
    keyPtr: CPointer<ByteVar>,
    keyLen: Int,
    ivPtr: CPointer<ByteVar>,
    ivLen: Int,
    aadPtr: CPointer<ByteVar>,
    aadLen: Int,
    dataIn: CPointer<ByteVar>,
    dataLen: Int,
    dataOut: CPointer<ByteVar>,
    tagOut: CPointer<ByteVar>,
) {
    memScoped {
        val refVar = alloc<CCCryptorRefVar>()
        var status =
            CCCryptorCreateWithMode(
                if (encrypt) kCCEncrypt else kCCDecrypt,
                kCCModeGCM.convert(),
                kCCAlgorithmAES,
                0.convert(), // ccNoPadding (GCM is unpadded)
                null, // IV is supplied separately via CCCryptorGCMAddIV
                keyPtr,
                keyLen.convert(),
                null,
                0.convert(), // no tweak
                0, // default rounds
                0.convert(), // no mode options
                refVar.ptr,
            )
        check(status == kCCSuccess) { "AES-GCM init failed (status=$status)" }
        val ref = refVar.value
        try {
            status = CCCryptorGCMAddIV(ref, ivPtr, ivLen.convert())
            check(status == kCCSuccess) { "AES-GCM set-IV failed (status=$status)" }
            if (aadLen > 0) {
                status = CCCryptorGCMaddAAD(ref, aadPtr, aadLen.convert())
                check(status == kCCSuccess) { "AES-GCM AAD failed (status=$status)" }
            }
            status =
                if (encrypt) {
                    CCCryptorGCMEncrypt(ref, dataIn, dataLen.convert(), dataOut)
                } else {
                    CCCryptorGCMDecrypt(ref, dataIn, dataLen.convert(), dataOut)
                }
            check(status == kCCSuccess) {
                "AES-GCM ${if (encrypt) "encrypt" else "decrypt"} failed (status=$status)"
            }
            val tagLen = alloc<size_tVar> { value = AEAD_TAG_BYTES.convert() }
            status = CCCryptorGCMFinal(ref, tagOut, tagLen.ptr)
            check(status == kCCSuccess) { "AES-GCM finalize failed (status=$status)" }
        } finally {
            CCCryptorRelease(ref)
        }
    }
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
