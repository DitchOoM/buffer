package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/*
 * Stateless PKCS#11 token primitives for [Tpm2Pkcs11HardwareKeyProvider] — key generation, the
 * on-token ECDH, the end-to-end agreement probe, and the SEC1/JCA conversions. Split from
 * HardwareKeys.jvm.kt, which retains the provider class and the typed resolution pipeline.
 */

internal fun generateP256KeyPair(p11: Provider): KeyPair =
    KeyPairGenerator
        .getInstance(EC_ALGORITHM, p11)
        .apply { initialize(ECGenParameterSpec(SECP256R1_CURVE)) }
        .generateKeyPair()

/**
 * Runs the token ECDH: `DH(privateKey, jcaPeer)` inside the TPM, returning the raw 32-byte shared
 * secret in a wiped `SecureBuffer` (the common seam applies the KDF / validation above it). A
 * peer-point rejection — checked or unchecked — maps to the uniform [InvalidPublicKey], never
 * distinguishable by exception type (oracle avoidance, matching the software JCA glue).
 */
@Suppress("SwallowedException", "TooGenericExceptionCaught", "ThrowsCount")
internal fun agreeP256OnToken(
    p11: Provider,
    privateKey: PrivateKey,
    jcaPeer: PublicKey,
): PlatformBuffer {
    val ka = KeyAgreement.getInstance(ECDH_ALGORITHM, p11)
    ka.init(privateKey)
    val rawSecretBytes =
        try {
            ka.doPhase(jcaPeer, true)
            ka.generateSecret()
        } catch (e: GeneralSecurityException) {
            throw InvalidPublicKey(KeyAgreementCurve.P256)
        } catch (e: RuntimeException) {
            throw InvalidPublicKey(KeyAgreementCurve.P256)
        }
    if (rawSecretBytes.size != P256_FIELD_BYTES) {
        rawSecretBytes.fill(0)
        throw HardwareKeyException.UnsupportedHardwareKey()
    }
    val raw = secureScratch.allocate(P256_FIELD_BYTES)
    raw.writeBytes(rawSecretBytes)
    rawSecretBytes.fill(0)
    raw.resetForRead()
    return raw
}

/** End-to-end ECDH probe: a token key must agree with a software peer, both directions matching. */
@Suppress("SwallowedException", "TooGenericExceptionCaught")
internal fun probeAgreement(p11: Provider): Boolean =
    try {
        val tokenPair = generateP256KeyPair(p11)
        val peer =
            KeyPairGenerator
                .getInstance(EC_ALGORITHM)
                .apply { initialize(ECGenParameterSpec(SECP256R1_CURVE)) }
                .generateKeyPair()

        val hw = KeyAgreement.getInstance(ECDH_ALGORITHM, p11)
        hw.init(tokenPair.private)
        hw.doPhase(peer.public, true)
        val hwSecret = hw.generateSecret()

        val sw = KeyAgreement.getInstance(ECDH_ALGORITHM)
        sw.init(peer.private)
        sw.doPhase(
            KeyFactory.getInstance(EC_ALGORITHM).generatePublic(X509EncodedKeySpec(tokenPair.public.encoded)),
            true,
        )
        val swSecret = sw.generateSecret()

        val match = hwSecret.contentEquals(swSecret)
        hwSecret.fill(0)
        swSecret.fill(0)
        match
    } catch (e: Throwable) {
        // NoSuchAlgorithmException (no CKM_ECDH1_DERIVE on the token) or any op failure: route
        // agreement to the software floor instead of over-promising hardware custody.
        false
    }

/**
 * Converts a peer's uncompressed SEC1 point (`04 || X || Y`, the pinned [KeyAgreementCurve]
 * encoding) to a JCA [PublicKey] via the default software provider. Malformed encodings and provider
 * rejections surface as the uniform [InvalidPublicKey] — same no-oracle contract as the software glue.
 */
@Suppress("SwallowedException", "TooGenericExceptionCaught", "ThrowsCount")
internal fun p256PublicKeyFromSec1(encoded: ReadBuffer): PublicKey {
    val curve = KeyAgreementCurve.P256
    val view = encoded.slice()
    val n = view.remaining()
    if (n != curve.publicKeyBytes || (view.get(view.position()).toInt() and UBYTE_MASK) != SEC1_UNCOMPRESSED) {
        throw InvalidPublicKey(curve)
    }
    // ByteArray at the unavoidable JCA seam (BigInteger/ECPoint), never a library data structure.
    val point = ByteArray(n)
    for (i in 0 until n) point[i] = view.get(view.position() + i)
    return try {
        val x = BigInteger(1, point.copyOfRange(1, 1 + P256_FIELD_BYTES))
        val y = BigInteger(1, point.copyOfRange(1 + P256_FIELD_BYTES, n))
        val params =
            AlgorithmParameters
                .getInstance(EC_ALGORITHM)
                .apply { init(ECGenParameterSpec(SECP256R1_CURVE)) }
                .getParameterSpec(ECParameterSpec::class.java)
        KeyFactory.getInstance(EC_ALGORITHM).generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    } catch (e: GeneralSecurityException) {
        throw InvalidPublicKey(curve)
    } catch (e: RuntimeException) {
        throw InvalidPublicKey(curve)
    }
}

/** Encodes an EC public key as an uncompressed SEC1 point (`04 || X || Y`), read-ready. */
internal fun uncompressedP256Point(pub: ECPublicKey): ReadBuffer {
    val out = ByteArray(1 + 2 * P256_FIELD_BYTES)
    out[0] = SEC1_UNCOMPRESSED.toByte()
    fixedBe(pub.w.affineX, P256_FIELD_BYTES).copyInto(out, 1)
    fixedBe(pub.w.affineY, P256_FIELD_BYTES).copyInto(out, 1 + P256_FIELD_BYTES)
    return BufferFactory.Default.wrap(out)
}

/** A nonnegative [BigInteger] as exactly [n] big-endian bytes (left-padded / sign-byte stripped). */
private fun fixedBe(
    value: BigInteger,
    n: Int,
): ByteArray {
    val be = value.toByteArray()
    return when {
        be.size == n -> be
        be.size == n + 1 && be[0].toInt() == 0 -> be.copyOfRange(1, be.size)
        be.size < n -> ByteArray(n - be.size) + be
        else -> be.copyOfRange(be.size - n, be.size)
    }
}

// Distinct names on purpose: same-named private constants ("EC", "secp256r1", field widths) exist in
// other files of this package, and these must be internal-visible to HardwareKeys.jvm.kt.
internal const val EC_ALGORITHM = "EC"
internal const val SECP256R1_CURVE = "secp256r1"
private const val ECDH_ALGORITHM = "ECDH"
private const val P256_FIELD_BYTES = 32
private const val SEC1_UNCOMPRESSED = 0x04
private const val UBYTE_MASK = 0xFF
