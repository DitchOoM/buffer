package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.managedMemoryAccess
import com.ditchoom.buffer.toByteArray
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/*
 * JVM/Android signature bridge over JCA.
 *
 * - ECDSA P-256/384/521 are wired here for both JVM and Android (shared jvmCommonMain), using
 *   Signature("SHA{256,384,512}withECDSA") and EC key specs. Encoding is DER/ASN.1 (JCA's native
 *   ECDSA signature format), pinned by ecdsaSignatureEncoding.
 * - Ed25519 uses JCA Signature("Ed25519") (JDK 15+ / Conscrypt on Android 14+). Whether it is
 *   available at all is decided per-platform by ed25519RuntimeSupported: a JDK probe on JVM, a
 *   Build.VERSION.SDK_INT >= 34 runtime gate on Android. When it is false, every Ed25519 op throws
 *   UnsupportedOperationException and supportsSyncEd25519 reports false.
 *
 * Key import: callers hand us the standard raw encoding (Ed25519 32-byte seed / public key, ECDSA
 * raw scalar / uncompressed SEC1 point). We assemble the JCA KeySpec from those at the boundary;
 * the only ByteArrays in this file are the ones the JDK's own APIs require/return.
 */

/** Decided per-platform: JVM probes the JDK, Android gates on `SDK_INT >= 34`. */
internal expect val ed25519RuntimeSupported: Boolean

actual val supportsSyncEd25519: Boolean
    get() = ed25519RuntimeSupported

actual val supportsSyncEcdsa: Boolean
    get() = true

actual val ecdsaSignatureEncoding: EcdsaSignatureEncoding
    get() = EcdsaSignatureEncoding.Der

// ---------------------------------------------------------------------------
// Byte extraction at the JCA boundary (non-destructive). Mirrors the hashes bridge:
// heap buffers expose their backing range, others copy via toByteArray().
// ---------------------------------------------------------------------------

private fun ReadBuffer.remainingBytes(): ByteArray {
    val managed = managedMemoryAccess
    return if (managed != null) {
        managed.backingArray.copyOfRange(
            managed.arrayOffset + position(),
            managed.arrayOffset + position() + remaining(),
        )
    } else {
        // toByteArray() returns this buffer's remaining bytes as an independent array.
        toByteArray()
    }
}

// ---------------------------------------------------------------------------
// EC curve parameters, looked up once per curve from the JCA provider.
// ---------------------------------------------------------------------------

private fun curveSpecName(scheme: SignatureScheme): String =
    when (scheme) {
        SignatureScheme.EcdsaP256 -> "secp256r1"
        SignatureScheme.EcdsaP384 -> "secp384r1"
        SignatureScheme.EcdsaP521 -> "secp521r1"
        SignatureScheme.Ed25519 -> error("not an EC curve")
    }

private fun ecParams(scheme: SignatureScheme): ECParameterSpec {
    val params = AlgorithmParameters.getInstance("EC")
    params.init(ECGenParameterSpec(curveSpecName(scheme)))
    return params.getParameterSpec(ECParameterSpec::class.java)
}

private fun ecdsaSigAlg(scheme: SignatureScheme): String =
    when (scheme) {
        SignatureScheme.EcdsaP256 -> "SHA256withECDSA"
        SignatureScheme.EcdsaP384 -> "SHA384withECDSA"
        SignatureScheme.EcdsaP521 -> "SHA512withECDSA"
        SignatureScheme.Ed25519 -> error("not ECDSA")
    }

private const val P256_FIELD_BYTES = 32
private const val P384_FIELD_BYTES = 48
private const val P521_FIELD_BYTES = 66
private const val ED25519_KEY_BYTES = 32
private const val SEC1_UNCOMPRESSED_POINT = 0x04

private const val HEX_RADIX = 16
private const val BYTE_MASK = 0xFF
private const val DER_TAG_SEQUENCE = 0x30
private const val DER_TAG_INTEGER = 0x02
private const val DER_LONG_FORM_FLAG = 0x80 // high bit set ⇒ long-form length / negative integer
private const val DER_LONG_FORM_ONE_OCTET = 0x81 // long form with a single following length octet
private const val DER_MIN_ECDSA_SIG_BYTES = 8 // smallest plausible SEQUENCE{INTEGER,INTEGER}
private const val DER_LONG_FORM_HEADER_BYTES = 3 // tag + 0x81 + one length octet

/** Field size in bytes for the scheme's curve (coordinate width of an uncompressed point). */
private fun ecFieldBytes(scheme: SignatureScheme): Int =
    when (scheme) {
        SignatureScheme.EcdsaP256 -> P256_FIELD_BYTES
        SignatureScheme.EcdsaP384 -> P384_FIELD_BYTES
        SignatureScheme.EcdsaP521 -> P521_FIELD_BYTES
        SignatureScheme.Ed25519 -> error("not ECDSA")
    }

// ---------------------------------------------------------------------------
// Ed25519 standard-encoding wrappers (RFC 8410). We wrap the raw 32-byte seed /
// public key in the fixed PKCS#8 / X.509 prefix so KeyFactory("Ed25519") accepts it
// without us depending on the JDK's EdEC point-decoding spec classes.
// ---------------------------------------------------------------------------

private val ED25519_PKCS8_PREFIX =
    byteArrayOf(
        0x30,
        0x2e,
        0x02,
        0x01,
        0x00,
        0x30,
        0x05,
        0x06,
        0x03,
        0x2b,
        0x65,
        0x70,
        0x04,
        0x22,
        0x04,
        0x20,
    )

private val ED25519_SPKI_PREFIX =
    byteArrayOf(
        0x30,
        0x2a,
        0x30,
        0x05,
        0x06,
        0x03,
        0x2b,
        0x65,
        0x70,
        0x03,
        0x21,
        0x00,
    )

private fun ed25519PrivateKey(seed: ByteArray): java.security.PrivateKey {
    require(seed.size == ED25519_KEY_BYTES) { "Ed25519 seed must be 32 bytes, was ${seed.size}" }
    val pkcs8 = ED25519_PKCS8_PREFIX + seed
    return KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
}

private fun ed25519PublicKey(pub: ByteArray): java.security.PublicKey {
    require(pub.size == ED25519_KEY_BYTES) { "Ed25519 public key must be 32 bytes, was ${pub.size}" }
    val spki = ED25519_SPKI_PREFIX + pub
    return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
}

// ---------------------------------------------------------------------------
// ECDSA key import from raw scalar / uncompressed SEC1 point.
// ---------------------------------------------------------------------------

private fun ecdsaPrivateKey(
    scheme: SignatureScheme,
    rawScalar: ByteArray,
): java.security.PrivateKey {
    // Positive-signum BigInteger so a high top bit is never read as negative.
    val d = BigInteger(1, rawScalar)
    val spec = ECPrivateKeySpec(d, ecParams(scheme))
    return KeyFactory.getInstance("EC").generatePrivate(spec)
}

private fun ecdsaPublicKey(
    scheme: SignatureScheme,
    point: ByteArray,
): java.security.PublicKey {
    val fieldBytes = ecFieldBytes(scheme)
    require(point.size == 1 + 2 * fieldBytes && point[0].toInt() == SEC1_UNCOMPRESSED_POINT) {
        "expected uncompressed SEC1 point (0x04 ‖ X ‖ Y) of ${1 + 2 * fieldBytes} bytes"
    }
    val x = BigInteger(1, point.copyOfRange(1, 1 + fieldBytes))
    val y = BigInteger(1, point.copyOfRange(1 + fieldBytes, point.size))
    // generatePublic validates the point is on the curve (off-curve ⇒ InvalidKeySpecException).
    val spec = ECPublicKeySpec(ECPoint(x, y), ecParams(scheme))
    return KeyFactory.getInstance("EC").generatePublic(spec)
}

// ---------------------------------------------------------------------------
// Sign / verify
// ---------------------------------------------------------------------------

private fun requireEd25519Supported() {
    if (!ed25519RuntimeSupported) {
        throw UnsupportedOperationException("Ed25519 is not available on this platform/runtime")
    }
}

private fun signatureFor(scheme: SignatureScheme): Signature =
    when (scheme) {
        SignatureScheme.Ed25519 -> {
            requireEd25519Supported()
            Signature.getInstance("Ed25519")
        }
        else -> Signature.getInstance(ecdsaSigAlg(scheme))
    }

private fun privateKeyFor(
    scheme: SignatureScheme,
    material: ByteArray,
): java.security.PrivateKey =
    when (scheme) {
        SignatureScheme.Ed25519 -> ed25519PrivateKey(material)
        else -> ecdsaPrivateKey(scheme, material)
    }

private fun publicKeyFor(
    scheme: SignatureScheme,
    material: ByteArray,
): java.security.PublicKey =
    when (scheme) {
        SignatureScheme.Ed25519 -> ed25519PublicKey(material)
        else -> ecdsaPublicKey(scheme, material)
    }

internal fun signToByteArray(
    key: SigningKey,
    message: ReadBuffer,
): ByteArray {
    val material =
        key.requireOpen().let { buf ->
            // material is read-ready; snapshot its bytes for the JCA spec.
            val start = buf.position()
            val n = buf.remaining()
            val out = ByteArray(n)
            for (i in 0 until n) out[i] = buf.get(start + i)
            out
        }
    val sig = signatureFor(key.scheme)
    sig.initSign(privateKeyFor(key.scheme, material))
    sig.update(message.remainingBytes())
    return sig.sign()
}

actual fun signInto(
    key: SigningKey,
    message: ReadBuffer,
    dest: WriteBuffer,
): Int {
    val signature = signToByteArray(key, message)
    require(dest.remaining() >= signature.size) {
        "dest needs ${signature.size} bytes remaining, has ${dest.remaining()}"
    }
    dest.writeBytes(signature)
    return signature.size
}

/**
 * Order `n` of each NIST curve, used to enforce `0 < r,s < n` so a verifier-side range check
 * rejects `r=0`/`s=0`/`r,s≥n` regardless of provider leniency.
 */
private fun curveOrder(scheme: SignatureScheme): BigInteger =
    when (scheme) {
        SignatureScheme.EcdsaP256 ->
            BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", HEX_RADIX)
        SignatureScheme.EcdsaP384 ->
            BigInteger(
                "ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf" +
                    "581a0db248b0a77aecec196accc52973",
                HEX_RADIX,
            )
        SignatureScheme.EcdsaP521 ->
            // SECG sec2-v2 P-521 order n (521-bit).
            BigInteger(
                "01ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" +
                    "fa51868783bf2f966b7fcc0148f709a5d03bb5c9b8899c47aebb6fb71e91386409",
                HEX_RADIX,
            )
        SignatureScheme.Ed25519 -> error("not ECDSA")
    }

/**
 * Strict canonical-DER check for an ECDSA `SEQUENCE { INTEGER r, INTEGER s }`. JCA's verifier is
 * lenient about non-canonical encodings (missing leading-zero pad, BER, trailing garbage), which is
 * a malleability vector — Wycheproof flags these `invalid`. We re-derive `r`/`s` from the bytes with
 * strict DER rules and reject anything non-canonical or with `r,s` out of `[1, n)`. Returns the
 * `(r, s)` pair iff the encoding is strictly canonical, else `null` (⇒ reject).
 */
private fun parseCanonicalEcdsaDer(
    sig: ByteArray,
    order: BigInteger,
): Pair<BigInteger, BigInteger>? {
    var p = 0

    fun u(i: Int) = sig[i].toInt() and BYTE_MASK
    if (sig.size < DER_MIN_ECDSA_SIG_BYTES || u(0) != DER_TAG_SEQUENCE) return null
    // SEQUENCE length: short form, or DER long form (1-byte length, used by P-521 ~139B sigs).
    val seqLen: Int
    if (u(1) < DER_LONG_FORM_FLAG) {
        seqLen = u(1)
        p = 2
    } else if (u(1) == DER_LONG_FORM_ONE_OCTET) {
        if (sig.size < DER_LONG_FORM_HEADER_BYTES) return null
        seqLen = u(2)
        if (seqLen < DER_LONG_FORM_FLAG) return null // long form must be minimal
        p = DER_LONG_FORM_HEADER_BYTES
    } else {
        return null // 2+ length octets not needed for these curves ⇒ reject
    }
    if (p + seqLen != sig.size) return null // exact match, no trailing garbage

    fun readInt(): BigInteger? {
        if (p + 2 > sig.size || u(p) != DER_TAG_INTEGER) return null
        val len = u(p + 1)
        if (len == 0 || len >= DER_LONG_FORM_FLAG) return null // empty or long-form ⇒ non-canonical
        val start = p + 2
        if (start + len > sig.size) return null
        // Minimal encoding: no leading 0x00 unless needed for sign bit; high bit ⇒ must be padded.
        if (len > 1 && u(start) == 0x00 && (u(start + 1) and DER_LONG_FORM_FLAG) == 0) return null
        if (u(start) and DER_LONG_FORM_FLAG != 0) return null // would be negative ⇒ non-canonical/illegal
        var v = BigInteger.ZERO
        for (i in 0 until len) v = v.shiftLeft(Byte.SIZE_BITS).or(BigInteger.valueOf(u(start + i).toLong()))
        p = start + len
        return v
    }

    val r = readInt() ?: return null
    val s = readInt() ?: return null
    if (p != sig.size) return null // trailing bytes ⇒ reject
    if (r < BigInteger.ONE || r >= order) return null
    if (s < BigInteger.ONE || s >= order) return null
    return r to s
}

internal fun verifyBytes(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean {
    if (key.scheme == SignatureScheme.Ed25519 && !ed25519RuntimeSupported) {
        throw UnsupportedOperationException("Ed25519 is not available on this platform/runtime")
    }
    val sigBytes = signature.remainingBytes()
    if (key.scheme.isEcdsa) {
        // Enforce strict canonical DER + r,s range before trusting the provider's verify.
        if (parseCanonicalEcdsaDer(sigBytes, curveOrder(key.scheme)) == null) return false
    }
    return try {
        val pub = publicKeyFor(key.scheme, key.material.remainingBytes())
        val sig = signatureFor(key.scheme)
        sig.initVerify(pub)
        sig.update(message.remainingBytes())
        sig.verify(sigBytes)
    } catch (_: GeneralSecurityException) {
        // Malformed signature / off-curve key / non-canonical DER ⇒ rejection, not acceptance.
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}

actual fun verify(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean = verifyBytes(key, message, signature)

// ---------------------------------------------------------------------------
// Async wrappers — native, so just fulfil synchronously.
// ---------------------------------------------------------------------------

actual suspend fun signAsync(
    key: SigningKey,
    message: ReadBuffer,
    factory: BufferFactory,
): ReadBuffer {
    val signature = signToByteArray(key, message)
    val out: PlatformBuffer = factory.allocate(signature.size)
    out.writeBytes(signature)
    out.resetForRead()
    return out
}

actual suspend fun verifyAsync(
    key: VerifyKey,
    message: ReadBuffer,
    signature: ReadBuffer,
): Boolean = verifyBytes(key, message, signature)

actual suspend fun ed25519AsyncAvailable(): Boolean = ed25519RuntimeSupported

actual val supportsEcdsaSigningFromScalar: Boolean
    get() = true
