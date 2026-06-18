@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.cinterop.boringssl.BCL_OK
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_ec_generate
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_ecdh
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_x25519
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_x25519_keypair
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.posix.size_tVar

/**
 * Linux key agreement over **BoringSSL** (libcrypto).
 *
 * **ECDH P-256/384/521** uses the low-level EC API: the private key is the raw big-endian scalar,
 * the peer public key the uncompressed SEC1 point `0x04 ‖ X ‖ Y`. The wrapper decodes the peer
 * point with `EC_POINT_oct2point` and explicitly rejects off-curve / infinity points, surfaced as
 * [InvalidPublicKey]. The raw secret is the big-endian X-coordinate of `priv · peer`, left-padded
 * to the field width (RFC 5903 / SEC1) — matching JCA and Apple.
 *
 * **X25519** uses BoringSSL `X25519` (RFC 7748 raw 32-byte scalar / u-coordinate). The library
 * returns 0 for a low-order / all-zero result; the shared [validateRawSecret] / [deriveFromRawSecret]
 * post-check also enforces the RFC 7748 §6.1 all-zero rejection.
 *
 * Private scalars are stored in a wiped [SecureBuffer] (via [secureScratch]).
 */

actual val supportsSyncX25519: Boolean = true
actual val supportsSyncEcdhP256: Boolean = true
actual val supportsSyncEcdhP384: Boolean = true
actual val supportsSyncEcdhP521: Boolean = true

private fun curveCode(curve: KeyAgreementCurve): Int =
    when (curve) {
        KeyAgreementCurve.P256 -> 256
        KeyAgreementCurve.P384 -> 384
        KeyAgreementCurve.P521 -> 521
        KeyAgreementCurve.X25519 -> error("X25519 is not a NIST curve code")
    }

actual fun generateKeyPair(curve: KeyAgreementCurve): KeyAgreementKeyPair =
    if (curve == KeyAgreementCurve.X25519) generateX25519() else generateEc(curve)

private fun generateX25519(): KeyAgreementKeyPair {
    val curve = KeyAgreementCurve.X25519
    val privBuf = secureScratch.allocate(curve.privateKeyBytes)
    val pubBuf = BufferFactory.Default.allocate(curve.publicKeyBytes)
    memScoped {
        val pubOut = allocArray<ByteVar>(curve.publicKeyBytes)
        val privOut = allocArray<ByteVar>(curve.privateKeyBytes)
        val status = bcl_x25519_keypair(pubOut.reinterpret(), privOut.reinterpret())
        check(status == BCL_OK) { "X25519 key generation failed (status=$status)" }
        for (i in 0 until curve.privateKeyBytes) privBuf.writeByte(privOut[i])
        for (i in 0 until curve.publicKeyBytes) pubBuf.writeByte(pubOut[i])
    }
    privBuf.resetForRead()
    pubBuf.resetForRead()
    return KeyAgreementKeyPair(
        curve,
        KeyAgreementPrivateKey(curve, privBuf),
        KeyAgreementPublicKey(curve, pubBuf),
    )
}

private fun generateEc(curve: KeyAgreementCurve): KeyAgreementKeyPair {
    val privBuf = secureScratch.allocate(curve.privateKeyBytes)
    val pubBuf = BufferFactory.Default.allocate(curve.publicKeyBytes)
    memScoped {
        val privOut = allocArray<ByteVar>(curve.privateKeyBytes)
        val pubOut = allocArray<ByteVar>(curve.publicKeyBytes)
        val privLen = alloc<size_tVar>()
        val pubLen = alloc<size_tVar>()
        val status =
            bcl_ec_generate(
                curveCode(curve),
                privOut.reinterpret(),
                curve.privateKeyBytes.convert(),
                privLen.ptr,
                pubOut.reinterpret(),
                curve.publicKeyBytes.convert(),
                pubLen.ptr,
            )
        check(status == BCL_OK) { "${curve.curveName} key generation failed (status=$status)" }
        check(privLen.value.toInt() == curve.privateKeyBytes) { "unexpected scalar length" }
        check(pubLen.value.toInt() == curve.publicKeyBytes) { "unexpected point length" }
        for (i in 0 until curve.privateKeyBytes) privBuf.writeByte(privOut[i])
        for (i in 0 until curve.publicKeyBytes) pubBuf.writeByte(pubOut[i])
    }
    privBuf.resetForRead()
    pubBuf.resetForRead()
    return KeyAgreementKeyPair(
        curve,
        KeyAgreementPrivateKey(curve, privBuf),
        KeyAgreementPublicKey(curve, pubBuf),
    )
}

actual fun deriveSharedSecret(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
    info: ReadBuffer,
    length: Int,
    salt: ReadBuffer?,
    factory: BufferFactory,
): ReadBuffer {
    val curve = privateKey.curve
    val raw = rawAgree(privateKey, peerPublicKey)
    return deriveFromRawSecret(curve, raw, info, length, salt, factory)
}

internal actual suspend fun dhRawSecret(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
): PlatformBuffer = validateRawSecret(privateKey.curve, rawAgree(privateKey, peerPublicKey))

/** Computes the raw DH secret into a wiped SecureBuffer; throws [InvalidPublicKey] on a bad point. */
private fun rawAgree(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
): PlatformBuffer {
    val curve = privateKey.curve
    require(curve == peerPublicKey.curve) { "private/public key curve mismatch" }
    return if (curve == KeyAgreementCurve.X25519) {
        rawAgreeX25519(privateKey, peerPublicKey)
    } else {
        rawAgreeEc(privateKey, peerPublicKey)
    }
}

private fun rawAgreeX25519(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
): PlatformBuffer {
    val curve = KeyAgreementCurve.X25519
    val out = secureScratch.allocate(curve.sharedSecretBytes)
    memScoped {
        val secretOut = allocArray<ByteVar>(curve.sharedSecretBytes)
        var status = -1
        privateKey.encoded.withRemainingBytes { privPtr, _ ->
            peerPublicKey.encoded.withRemainingBytes { peerPtr, _ ->
                status = bcl_x25519(privPtr.reinterpret(), peerPtr.reinterpret(), secretOut.reinterpret())
            }
        }
        if (status != BCL_OK) {
            out.freeNativeMemory()
            throw InvalidPublicKey(curve.curveName)
        }
        for (i in 0 until curve.sharedSecretBytes) out.writeByte(secretOut[i])
    }
    out.resetForRead()
    return out
}

private fun rawAgreeEc(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
): PlatformBuffer {
    val curve = privateKey.curve
    val out = secureScratch.allocate(curve.sharedSecretBytes)
    memScoped {
        val secretOut = allocArray<ByteVar>(curve.sharedSecretBytes)
        val secretLen = alloc<size_tVar>()
        var status = -1
        privateKey.encoded.withRemainingBytes { privPtr, privLen ->
            peerPublicKey.encoded.withRemainingBytes { peerPtr, peerLen ->
                status =
                    bcl_ecdh(
                        curveCode(curve),
                        privPtr.reinterpret(),
                        privLen.convert(),
                        peerPtr.reinterpret(),
                        peerLen.convert(),
                        secretOut.reinterpret(),
                        curve.sharedSecretBytes.convert(),
                        secretLen.ptr,
                    )
            }
        }
        if (status != BCL_OK || secretLen.value.toInt() != curve.sharedSecretBytes) {
            out.freeNativeMemory()
            throw InvalidPublicKey(curve.curveName)
        }
        for (i in 0 until curve.sharedSecretBytes) out.writeByte(secretOut[i])
    }
    out.resetForRead()
    return out
}

actual suspend fun generateKeyPairAsync(curve: KeyAgreementCurve): KeyAgreementKeyPair = generateKeyPair(curve)

actual suspend fun deriveSharedSecretAsync(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
    info: ReadBuffer,
    length: Int,
    salt: ReadBuffer?,
    factory: BufferFactory,
): ReadBuffer = deriveSharedSecret(privateKey, peerPublicKey, info, length, salt, factory)
