@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.crypto.cinterop.boringssl.BCL_AUTH_FAIL
import com.ditchoom.buffer.crypto.cinterop.boringssl.BCL_OK
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_aes_gcm_open
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_aes_gcm_seal
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_chacha_open
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_chacha_seal
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.posix.size_tVar

/*
 * Linux AEAD backed by BoringSSL's one-shot EVP_AEAD API.
 *
 * AES-128/256-GCM and ChaCha20-Poly1305 use EVP_AEAD_CTX_seal / EVP_AEAD_CTX_open with the
 * RFC 5116 layout (ciphertext immediately followed by the 16-byte tag). On the open path
 * BoringSSL authenticates first and writes plaintext only on a verified tag — so there is no
 * unverified-plaintext release and no manual constant-time tag compare is needed; a failure
 * surfaces as BCL_AUTH_FAIL and we raise the opaque VerificationFailed.
 */

actual val supportsSyncAesGcm: Boolean = true
actual val supportsChaChaPoly: Boolean = true
actual val supportsSyncChaChaPoly: Boolean = true

private fun requireNonce(nonce: ReadBuffer) {
    require(nonce.remaining() == AEAD_NONCE_BYTES) {
        "nonce must be $AEAD_NONCE_BYTES bytes, was ${nonce.remaining()}"
    }
}

/** A seal/open BoringSSL wrapper, distilled to the common pointer signature. */
private typealias AeadFn = (
    key: CPointer<ByteVar>, keyLen: Int,
    nonce: CPointer<ByteVar>, nonceLen: Int,
    aad: CPointer<ByteVar>, aadLen: Int,
    data: CPointer<ByteVar>, dataLen: Int,
    out: CPointer<ByteVar>, outCap: Int, outLen: CPointer<size_tVar>,
) -> Int

private fun aeadSeal(
    keyMaterial: ReadBuffer,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    dest: WriteBuffer,
    seal: AeadFn,
) {
    requireNonce(nonce)
    val ptLen = plaintext.remaining()
    val sealedLen = ptLen + AEAD_TAG_BYTES
    require(dest.remaining() >= sealedLen) {
        "dest needs $sealedLen bytes remaining, has ${dest.remaining()}"
    }
    memScoped {
        val outLen = alloc<size_tVar>()
        var status = BCL_OK - 1
        keyMaterial.withRemainingBytes { keyPtr, keyLen ->
            nonce.withRemainingBytes { ivPtr, ivLen ->
                withOptionalBytes(aad) { aadPtr, aadLen ->
                    plaintext.withRemainingBytes2(ptLen) { ptPtr ->
                        // EVP_AEAD_CTX_seal writes ciphertext||tag contiguously straight into dest.
                        dest.withWritablePointer(sealedLen) { outPtr ->
                            status =
                                seal(
                                    keyPtr, keyLen,
                                    ivPtr, ivLen,
                                    aadPtr, aadLen,
                                    ptPtr, ptLen,
                                    outPtr, sealedLen, outLen.ptr,
                                )
                        }
                    }
                }
            }
        }
        check(status == BCL_OK) { "AEAD seal failed (status=$status)" }
        check(outLen.value.toInt() == sealedLen) { "AEAD seal produced ${outLen.value} bytes, expected $sealedLen" }
    }
}

private fun aeadOpen(
    keyMaterial: ReadBuffer,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    dest: WriteBuffer,
    open: AeadFn,
) {
    requireNonce(nonce)
    require(ciphertextAndTag.remaining() >= AEAD_TAG_BYTES) {
        "ciphertext+tag must be at least $AEAD_TAG_BYTES bytes"
    }
    val ctLen = ciphertextAndTag.remaining()
    val ptLen = ctLen - AEAD_TAG_BYTES
    require(dest.remaining() >= ptLen) { "dest needs $ptLen bytes remaining, has ${dest.remaining()}" }
    memScoped {
        val outLen = alloc<size_tVar>()
        var status = BCL_OK - 1
        keyMaterial.withRemainingBytes { keyPtr, keyLen ->
            nonce.withRemainingBytes { ivPtr, ivLen ->
                withOptionalBytes(aad) { aadPtr, aadLen ->
                    ciphertextAndTag.withRemainingBytes2(ctLen) { ctPtr ->
                        // EVP_AEAD_CTX_open authenticates before releasing any plaintext; on a tag
                        // mismatch it returns 0 (mapped to BCL_AUTH_FAIL) and never writes dest.
                        dest.withWritablePointer(ptLen) { outPtr ->
                            status =
                                open(
                                    keyPtr, keyLen,
                                    ivPtr, ivLen,
                                    aadPtr, aadLen,
                                    ctPtr, ctLen,
                                    outPtr, ptLen, outLen.ptr,
                                )
                        }
                    }
                }
            }
        }
        if (status == BCL_AUTH_FAIL) throw VerificationFailed()
        check(status == BCL_OK) { "AEAD open failed (status=$status)" }
        check(outLen.value.toInt() == ptLen) { "AEAD open produced ${outLen.value} bytes, expected $ptLen" }
    }
}

internal actual fun aesGcmSeal(
    key: AesGcmKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    dest: WriteBuffer,
) = aeadSeal(key.material, nonce, aad, plaintext, dest) {
        keyPtr, keyLen, ivPtr, ivLen, aadPtr, aadLen, ptPtr, ptLen, outPtr, outCap, outLen ->
    bcl_aes_gcm_seal(
        keyPtr.reinterpret(), keyLen.convert(),
        ivPtr.reinterpret(), ivLen.convert(),
        aadPtr.reinterpret(), aadLen.convert(),
        ptPtr.reinterpret(), ptLen.convert(),
        outPtr.reinterpret(), outCap.convert(), outLen,
    )
}

internal actual fun aesGcmOpen(
    key: AesGcmKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    dest: WriteBuffer,
) = aeadOpen(key.material, nonce, aad, ciphertextAndTag, dest) {
        keyPtr, keyLen, ivPtr, ivLen, aadPtr, aadLen, ctPtr, ctLen, outPtr, outCap, outLen ->
    bcl_aes_gcm_open(
        keyPtr.reinterpret(), keyLen.convert(),
        ivPtr.reinterpret(), ivLen.convert(),
        aadPtr.reinterpret(), aadLen.convert(),
        ctPtr.reinterpret(), ctLen.convert(),
        outPtr.reinterpret(), outCap.convert(), outLen,
    )
}

internal actual fun chaChaPolySeal(
    key: ChaChaPolyKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    dest: WriteBuffer,
) = aeadSeal(key.material, nonce, aad, plaintext, dest) {
        keyPtr, keyLen, ivPtr, ivLen, aadPtr, aadLen, ptPtr, ptLen, outPtr, outCap, outLen ->
    bcl_chacha_seal(
        keyPtr.reinterpret(), keyLen.convert(),
        ivPtr.reinterpret(), ivLen.convert(),
        aadPtr.reinterpret(), aadLen.convert(),
        ptPtr.reinterpret(), ptLen.convert(),
        outPtr.reinterpret(), outCap.convert(), outLen,
    )
}

internal actual fun chaChaPolyOpen(
    key: ChaChaPolyKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    dest: WriteBuffer,
) = aeadOpen(key.material, nonce, aad, ciphertextAndTag, dest) {
        keyPtr, keyLen, ivPtr, ivLen, aadPtr, aadLen, ctPtr, ctLen, outPtr, outCap, outLen ->
    bcl_chacha_open(
        keyPtr.reinterpret(), keyLen.convert(),
        ivPtr.reinterpret(), ivLen.convert(),
        aadPtr.reinterpret(), aadLen.convert(),
        ctPtr.reinterpret(), ctLen.convert(),
        outPtr.reinterpret(), outCap.convert(), outLen,
    )
}

// Async wrappers — Linux has a synchronous native AEAD, so delegate to the framed one-shots.

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
