@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.crypto.cinterop.boringssl.BCL_OK
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_ecdsa_sign
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_ecdsa_verify
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_ed25519_sign
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_ed25519_verify
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
 * Linux signature bridge over **BoringSSL** (libcrypto).
 *
 * **ECDSA** P-256/384/521: hash-then-sign with SHA-256/384/512 via `EVP_DigestSign`, producing and
 * consuming **DER/X9.62** signatures — so [ecdsaSignatureEncoding] is `Der`, matching JVM and Apple.
 * The signing key is the raw private scalar ([supportsEcdsaSigningFromScalar] = `true`); the verify
 * key is the uncompressed SEC1 point `0x04 ‖ X ‖ Y`.
 *
 * BoringSSL's verify is already strict about DER, but to hold the exact cross-platform contract (and
 * the Wycheproof `invalid` vectors for non-canonical BER, superfluous padding, trailing bytes, and
 * `r`/`s` out of `[1, n)`) we enforce strict canonical DER + range ourselves before delegating, the
 * same gate the JVM backend applies.
 *
 * **Ed25519**: raw 32-byte seed / public key, 64-byte signature, via BoringSSL `ED25519_sign` /
 * `ED25519_verify`. [supportsSyncEd25519] is `true`.
 */

actual val supportsSyncEd25519: Boolean = true
actual val supportsSyncEcdsa: Boolean = true
actual val ecdsaSignatureEncoding: EcdsaSignatureEncoding = EcdsaSignatureEncoding.Der
actual val supportsEcdsaSigningFromScalar: Boolean = true

private const val P256_CURVE_BITS = 256
private const val P384_CURVE_BITS = 384
private const val P521_CURVE_BITS = 521
private const val ED25519_KEY_BYTES = 32
private const val ED25519_SIGNATURE_BYTES = 64

private const val HEX_RADIX = 16
private const val BYTE_MASK = 0xFF
private const val DER_TAG_SEQUENCE = 0x30
private const val DER_TAG_INTEGER = 0x02
private const val DER_LONG_FORM_FLAG = 0x80 // high bit set ⇒ long-form length / negative integer
private const val DER_LONG_FORM_ONE_OCTET = 0x81 // long form with a single following length octet
private const val DER_MIN_ECDSA_SIG_BYTES = 8 // smallest plausible SEQUENCE{INTEGER,INTEGER}
private const val DER_LONG_FORM_HEADER_BYTES = 3 // tag + 0x81 + one length octet

private fun ecdsaCurveCode(scheme: SignatureScheme): Int =
    when (scheme) {
        SignatureScheme.EcdsaP256 -> P256_CURVE_BITS
        SignatureScheme.EcdsaP384 -> P384_CURVE_BITS
        SignatureScheme.EcdsaP521 -> P521_CURVE_BITS
        SignatureScheme.Ed25519 -> error("not an ECDSA scheme")
    }

// ---------------------------------------------------------------------------
// Strict canonical-DER ECDSA-signature validation (matches Signatures.jvmCommon).
// ---------------------------------------------------------------------------

/** Curve order n; r and s must lie in [1, n). */
private fun curveOrderHex(scheme: SignatureScheme): String =
    when (scheme) {
        SignatureScheme.EcdsaP256 ->
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551"
        SignatureScheme.EcdsaP384 ->
            "ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf" +
                "581a0db248b0a77aecec196accc52973"
        SignatureScheme.EcdsaP521 ->
            "01ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" +
                "fa51868783bf2f966b7fcc0148f709a5d03bb5c9b8899c47aebb6fb71e91386409"
        SignatureScheme.Ed25519 -> error("not ECDSA")
    }

/** Big-endian unsigned compare of [value] (DER integer bytes, minimal) against the hex order. */
private fun inRangeOneToOrder(
    value: ByteArray,
    orderHex: String,
): Boolean {
    // value is a minimal big-endian magnitude with no leading zero (canonical DER, sign bit clear).
    // Reject zero.
    if (value.all { it.toInt() == 0 }) return false
    // Build order bytes (even-length hex, big-endian).
    val order = ByteArray(orderHex.length / 2) { i -> orderHex.substring(i * 2, i * 2 + 2).toInt(HEX_RADIX).toByte() }
    // Strip leading zeros from order for a magnitude compare.
    val orderMag = order.dropWhile { it.toInt() == 0 }.toByteArray()
    val valMag = value.dropWhile { it.toInt() == 0 }.toByteArray()
    if (valMag.size != orderMag.size) return valMag.size < orderMag.size
    for (i in valMag.indices) {
        val a = valMag[i].toInt() and BYTE_MASK
        val b = orderMag[i].toInt() and BYTE_MASK
        if (a != b) return a < b
    }
    return false // equal to order ⇒ not < n
}

/**
 * Strict canonical-DER check for `SEQUENCE { INTEGER r, INTEGER s }` with `r,s ∈ [1, n)`. Returns
 * true iff the signature is strictly canonical. Mirrors `parseCanonicalEcdsaDer` on the JVM.
 */
private fun isCanonicalEcdsaDer(
    signature: ReadBuffer,
    scheme: SignatureScheme,
): Boolean {
    val start = signature.position()
    val n = signature.remaining()
    if (n < DER_MIN_ECDSA_SIG_BYTES) return false
    val b = ByteArray(n) { signature.get(start + it) }

    fun u(i: Int) = b[i].toInt() and BYTE_MASK
    if (u(0) != DER_TAG_SEQUENCE) return false
    var p: Int
    val seqLen: Int
    when {
        u(1) < DER_LONG_FORM_FLAG -> {
            seqLen = u(1)
            p = 2
        }
        u(1) == DER_LONG_FORM_ONE_OCTET -> {
            if (n < DER_LONG_FORM_HEADER_BYTES) return false
            seqLen = u(2)
            if (seqLen < DER_LONG_FORM_FLAG) return false // long form must be minimal
            p = DER_LONG_FORM_HEADER_BYTES
        }
        else -> return false
    }
    if (p + seqLen != n) return false

    fun readInt(): ByteArray? {
        if (p + 2 > n || u(p) != DER_TAG_INTEGER) return null
        val len = u(p + 1)
        if (len == 0 || len >= DER_LONG_FORM_FLAG) return null
        val s = p + 2
        if (s + len > n) return null
        if (len > 1 && u(s) == 0x00 && (u(s + 1) and DER_LONG_FORM_FLAG) == 0) return null // superfluous 0x00
        if (u(s) and DER_LONG_FORM_FLAG != 0) return null // negative
        val out = ByteArray(len) { b[s + it] }
        p = s + len
        return out
    }

    val r = readInt() ?: return false
    val s = readInt() ?: return false
    if (p != n) return false
    val orderHex = curveOrderHex(scheme)
    return inRangeOneToOrder(r, orderHex) && inRangeOneToOrder(s, orderHex)
}

// ---------------------------------------------------------------------------
// Sign / verify
// ---------------------------------------------------------------------------

private fun ed25519Sign(
    key: SigningKey,
    message: ReadBuffer,
): ByteArray {
    val seed = key.requireOpen()
    require(seed.remaining() == ED25519_KEY_BYTES) { "Ed25519 seed must be 32 bytes" }
    val msgLen = message.remaining()
    return memScoped {
        val sigOut = allocArray<ByteVar>(ED25519_SIGNATURE_BYTES)
        var status = -1
        seed.withRemainingBytes { seedPtr, _ ->
            message.withRemainingBytes2(msgLen) { msgPtr ->
                status =
                    bcl_ed25519_sign(
                        seedPtr.reinterpret(),
                        msgPtr.reinterpret(),
                        msgLen.convert(),
                        sigOut.reinterpret(),
                    )
            }
        }
        check(status == BCL_OK) { "Ed25519 sign failed (status=$status)" }
        ByteArray(ED25519_SIGNATURE_BYTES) { sigOut[it] }
    }
}

private fun ecdsaSign(
    key: SigningKey,
    message: ReadBuffer,
): ByteArray {
    val scalar = key.requireOpen()
    val cap = maxSignatureBytes(key.scheme)
    val curveCode = ecdsaCurveCode(key.scheme)
    val msgLen = message.remaining()
    return memScoped {
        val sigOut = allocArray<ByteVar>(cap)
        val sigLen = alloc<size_tVar>()
        var status = -1
        scalar.withRemainingBytes { keyPtr, keyLen ->
            message.withRemainingBytes2(msgLen) { msgPtr ->
                status =
                    bcl_ecdsa_sign(
                        curveCode,
                        keyPtr.reinterpret(),
                        keyLen.convert(),
                        msgPtr.reinterpret(),
                        msgLen.convert(),
                        sigOut.reinterpret(),
                        cap.convert(),
                        sigLen.ptr,
                    )
            }
        }
        check(status == BCL_OK) { "ECDSA sign failed (status=$status)" }
        val n = sigLen.value.toInt()
        ByteArray(n) { sigOut[it] }
    }
}

private fun signToByteArray(
    key: SigningKey,
    message: ReadBuffer,
): ByteArray =
    if (key.scheme == SignatureScheme.Ed25519) {
        ed25519Sign(key, message)
    } else {
        ecdsaSign(key, message)
    }

private fun ed25519Verify(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean {
    if (key.material.remaining() != ED25519_KEY_BYTES) return false
    if (signature.remaining() != ED25519_SIGNATURE_BYTES) return false
    val msgLen = message.remaining()
    var status = -1
    key.material.withRemainingBytes { pubPtr, _ ->
        message.withRemainingBytes2(msgLen) { msgPtr ->
            signature.withRemainingBytes { sigPtr, _ ->
                status =
                    bcl_ed25519_verify(
                        pubPtr.reinterpret(),
                        msgPtr.reinterpret(),
                        msgLen.convert(),
                        sigPtr.reinterpret(),
                    )
            }
        }
    }
    return status == BCL_OK
}

private fun ecdsaVerify(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean {
    // Enforce strict canonical DER + r,s range before trusting BoringSSL's verify.
    if (!isCanonicalEcdsaDer(signature, key.scheme)) return false
    val curveCode = ecdsaCurveCode(key.scheme)
    val msgLen = message.remaining()
    var status = -1
    key.material.withRemainingBytes { pubPtr, pubLen ->
        message.withRemainingBytes2(msgLen) { msgPtr ->
            signature.withRemainingBytes { sigPtr, sigLen ->
                status =
                    bcl_ecdsa_verify(
                        curveCode,
                        pubPtr.reinterpret(),
                        pubLen.convert(),
                        msgPtr.reinterpret(),
                        msgLen.convert(),
                        sigPtr.reinterpret(),
                        sigLen.convert(),
                    )
            }
        }
    }
    return status == BCL_OK
}

actual fun signInto(
    key: SigningKey,
    message: ReadBuffer,
    dest: WriteBuffer,
): Int {
    val sig = signToByteArray(key, message)
    require(dest.remaining() >= sig.size) { "dest needs ${sig.size} bytes remaining, has ${dest.remaining()}" }
    dest.writeBytes(sig)
    return sig.size
}

actual fun verify(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean =
    if (key.scheme == SignatureScheme.Ed25519) {
        ed25519Verify(key, message, signature)
    } else {
        ecdsaVerify(key, message, signature)
    }

actual suspend fun signAsync(
    key: SigningKey,
    message: ReadBuffer,
    factory: BufferFactory,
): ReadBuffer {
    val sig = signToByteArray(key, message)
    val out = factory.allocate(sig.size)
    out.writeBytes(sig)
    out.resetForRead()
    return out
}

actual suspend fun verifyAsync(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean = verify(key, message, signature)

actual suspend fun ed25519AsyncAvailable(): Boolean = true
