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

/** AES-GCM is synchronous via BoringSSL's one-shot EVP_AEAD. */
actual val CryptoCapabilities.aesGcm: Aead<AesGcmKey, SyncCapableAesGcmKey> get() = Aead.Blocking(AesGcmBlockingOps)

/** ChaCha20-Poly1305 is synchronous via BoringSSL on Linux. */
actual val CryptoCapabilities.chaChaPoly: OptionalAead<ChaChaPolyKey>
    get() = OptionalAead.Blocking(ChaChaPolyBlockingOps)

/** Which BoringSSL AEAD wrapper a seal/open call dispatches to. */
private enum class AeadAlg { AES_GCM, CHACHA }

private fun requireNonce(nonce: ReadBuffer) {
    require(nonce.remaining() == AEAD_NONCE_BYTES) {
        "nonce must be $AEAD_NONCE_BYTES bytes, was ${nonce.remaining()}"
    }
}

private fun sealCall(
    alg: AeadAlg,
    key: CPointer<ByteVar>,
    keyLen: Int,
    nonce: CPointer<ByteVar>,
    nonceLen: Int,
    aad: CPointer<ByteVar>,
    aadLen: Int,
    data: CPointer<ByteVar>,
    dataLen: Int,
    out: CPointer<ByteVar>,
    outCap: Int,
    outLen: CPointer<size_tVar>,
): Int =
    when (alg) {
        AeadAlg.AES_GCM ->
            bcl_aes_gcm_seal(
                key.reinterpret(),
                keyLen.convert(),
                nonce.reinterpret(),
                nonceLen.convert(),
                aad.reinterpret(),
                aadLen.convert(),
                data.reinterpret(),
                dataLen.convert(),
                out.reinterpret(),
                outCap.convert(),
                outLen,
            )
        AeadAlg.CHACHA ->
            bcl_chacha_seal(
                key.reinterpret(),
                keyLen.convert(),
                nonce.reinterpret(),
                nonceLen.convert(),
                aad.reinterpret(),
                aadLen.convert(),
                data.reinterpret(),
                dataLen.convert(),
                out.reinterpret(),
                outCap.convert(),
                outLen,
            )
    }

private fun openCall(
    alg: AeadAlg,
    key: CPointer<ByteVar>,
    keyLen: Int,
    nonce: CPointer<ByteVar>,
    nonceLen: Int,
    aad: CPointer<ByteVar>,
    aadLen: Int,
    data: CPointer<ByteVar>,
    dataLen: Int,
    out: CPointer<ByteVar>,
    outCap: Int,
    outLen: CPointer<size_tVar>,
): Int =
    when (alg) {
        AeadAlg.AES_GCM ->
            bcl_aes_gcm_open(
                key.reinterpret(),
                keyLen.convert(),
                nonce.reinterpret(),
                nonceLen.convert(),
                aad.reinterpret(),
                aadLen.convert(),
                data.reinterpret(),
                dataLen.convert(),
                out.reinterpret(),
                outCap.convert(),
                outLen,
            )
        AeadAlg.CHACHA ->
            bcl_chacha_open(
                key.reinterpret(),
                keyLen.convert(),
                nonce.reinterpret(),
                nonceLen.convert(),
                aad.reinterpret(),
                aadLen.convert(),
                data.reinterpret(),
                dataLen.convert(),
                out.reinterpret(),
                outCap.convert(),
                outLen,
            )
    }

private fun aeadSeal(
    alg: AeadAlg,
    keyMaterial: ReadBuffer,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    dest: WriteBuffer,
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
                                sealCall(
                                    alg,
                                    keyPtr,
                                    keyLen,
                                    ivPtr,
                                    ivLen,
                                    aadPtr,
                                    aadLen,
                                    ptPtr,
                                    ptLen,
                                    outPtr,
                                    sealedLen,
                                    outLen.ptr,
                                )
                        }
                    }
                }
            }
        }
        check(status == BCL_OK) { "AEAD seal failed (status=$status)" }
        check(outLen.value.toInt() == sealedLen) {
            "AEAD seal produced ${outLen.value} bytes, expected $sealedLen"
        }
    }
}

private fun aeadOpen(
    alg: AeadAlg,
    keyMaterial: ReadBuffer,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    dest: WriteBuffer,
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
                                openCall(
                                    alg,
                                    keyPtr,
                                    keyLen,
                                    ivPtr,
                                    ivLen,
                                    aadPtr,
                                    aadLen,
                                    ctPtr,
                                    ctLen,
                                    outPtr,
                                    ptLen,
                                    outLen.ptr,
                                )
                        }
                    }
                }
            }
        }
        if (status == BCL_AUTH_FAIL) throw VerificationFailed()
        check(status == BCL_OK) { "AEAD open failed (status=$status)" }
        check(outLen.value.toInt() == ptLen) {
            "AEAD open produced ${outLen.value} bytes, expected $ptLen"
        }
    }
}

internal actual fun aesGcmSeal(
    key: AesGcmKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    dest: WriteBuffer,
) = aeadSeal(AeadAlg.AES_GCM, key.requireInMemoryMaterial(), nonce, aad, plaintext, dest)

internal actual fun aesGcmOpen(
    key: AesGcmKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    dest: WriteBuffer,
) = aeadOpen(AeadAlg.AES_GCM, key.requireInMemoryMaterial(), nonce, aad, ciphertextAndTag, dest)

internal actual fun chaChaPolySeal(
    key: ChaChaPolyKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    dest: WriteBuffer,
) = aeadSeal(AeadAlg.CHACHA, key.requireInMemoryMaterial(), nonce, aad, plaintext, dest)

internal actual fun chaChaPolyOpen(
    key: ChaChaPolyKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    dest: WriteBuffer,
) = aeadOpen(AeadAlg.CHACHA, key.requireInMemoryMaterial(), nonce, aad, ciphertextAndTag, dest)

// Async with-nonce seam — Linux has a synchronous native AEAD, so delegate to the sync primitives.

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
