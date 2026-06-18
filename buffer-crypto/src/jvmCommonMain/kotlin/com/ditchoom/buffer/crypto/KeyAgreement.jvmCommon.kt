package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.interfaces.XECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement as JcaKeyAgreement

/**
 * JVM/Android key agreement over the JCA: `XDH` for X25519 (JDK 11+) and `ECDH` for the NIST
 * P-curves. Public keys are converted between the pinned raw encoding ([KeyAgreementCurve]) and
 * JCA's SPKI/X.509 / `ECPoint` forms here.
 *
 * Android note: X25519 is only present from API 34 (Conscrypt added it in Android 14). The
 * capability flags below probe the provider at class-load time, so on API 28–33 [supportsSyncX25519]
 * is `false` and the X25519 entry points throw [UnsupportedOperationException] — JVM is unaffected.
 *
 * `ByteArray` appears in this file at the unavoidable JCA seam (X509EncodedKeySpec / generateSecret /
 * BigInteger) — the same system-API boundary the landed `JvmCryptoBridge` lives at; it is never used
 * as a data structure in library logic, and all secret-bearing arrays are wiped after use.
 */

private val x25519SpkiPrefix: ByteArray =
    // SPKI prefix for an X25519 SubjectPublicKeyInfo: AlgorithmIdentifier(id-X25519) + BIT STRING header.
    byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00)

private fun probe(block: () -> Unit): Boolean =
    try {
        block()
        true
    } catch (_: Throwable) {
        false
    }

actual val supportsSyncX25519: Boolean =
    probe {
        KeyPairGenerator.getInstance("XDH").initialize(NamedParameterSpec.X25519)
        JcaKeyAgreement.getInstance("XDH")
    }

private fun probeEc(curve: String): Boolean =
    probe {
        KeyPairGenerator.getInstance("EC").initialize(ECGenParameterSpec(curve))
        JcaKeyAgreement.getInstance("ECDH")
    }

actual val supportsSyncEcdhP256: Boolean = probeEc("secp256r1")
actual val supportsSyncEcdhP384: Boolean = probeEc("secp384r1")
actual val supportsSyncEcdhP521: Boolean = probeEc("secp521r1")

private fun jcaCurveName(curve: KeyAgreementCurve): String =
    when (curve) {
        KeyAgreementCurve.P256 -> "secp256r1"
        KeyAgreementCurve.P384 -> "secp384r1"
        KeyAgreementCurve.P521 -> "secp521r1"
        KeyAgreementCurve.X25519 -> error("X25519 is not an EC named curve")
    }

private fun requireSupported(curve: KeyAgreementCurve) {
    if (!supportsSync(curve)) {
        throw UnsupportedOperationException("${curve.curveName} key agreement is not available on this platform")
    }
}

/** Reads a buffer's remaining bytes into a fresh big-endian [ByteArray] without disturbing it. */
private fun ReadBuffer.toBytes(): ByteArray {
    val start = position()
    val n = remaining()
    val out = ByteArray(n)
    for (i in 0 until n) out[i] = get(start + i)
    return out
}

/** Positive [BigInteger] from a big-endian fixed-width [bytes] slice. */
private fun beInt(
    bytes: ByteArray,
    offset: Int,
    len: Int,
): BigInteger {
    val slice = ByteArray(len)
    System.arraycopy(bytes, offset, slice, 0, len)
    return BigInteger(1, slice)
}

/** EC domain parameters for [curve], obtained without generating a key pair. */
private fun ecParams(curve: KeyAgreementCurve): ECParameterSpec {
    val ap = AlgorithmParameters.getInstance("EC")
    ap.init(ECGenParameterSpec(jcaCurveName(curve)))
    return ap.getParameterSpec(ECParameterSpec::class.java)
}

// ---- X25519 -----------------------------------------------------------------

private fun x25519PublicKey(raw: ByteArray): java.security.PublicKey {
    val spki = ByteArray(x25519SpkiPrefix.size + 32)
    System.arraycopy(x25519SpkiPrefix, 0, spki, 0, x25519SpkiPrefix.size)
    System.arraycopy(raw, 0, spki, x25519SpkiPrefix.size, 32)
    return KeyFactory.getInstance("XDH").generatePublic(X509EncodedKeySpec(spki))
}

private fun x25519PrivateKeyFrom(scalar: ByteArray): java.security.PrivateKey {
    // PKCS#8 wrapper: PrivateKeyInfo(version, id-X25519, OCTET STRING { CurvePrivateKey OCTET STRING }).
    val pkcs8Prefix =
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
            0x6e,
            0x04,
            0x22,
            0x04,
            0x20,
        )
    val der = ByteArray(pkcs8Prefix.size + 32)
    System.arraycopy(pkcs8Prefix, 0, der, 0, pkcs8Prefix.size)
    System.arraycopy(scalar, 0, der, pkcs8Prefix.size, 32)
    return KeyFactory.getInstance("XDH").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(der))
}

/** Little-endian 32-byte raw u from an XEC public key's BigInteger u-coordinate. */
private fun rawX25519(pub: java.security.PublicKey): ByteArray {
    val u = (pub as XECPublicKey).u
    val be = u.toByteArray() // big-endian, possibly with sign byte / shorter than 32
    val raw = ByteArray(32)
    // copy big-endian magnitude into the low bytes, then reverse to little-endian
    var src = be.size - 1
    var dst = 0
    while (src >= 0 && dst < 32) {
        raw[dst] = be[src]
        src--
        dst++
    }
    return raw
}

// ---- ECDH -------------------------------------------------------------------

private fun ecPublicKey(
    curve: KeyAgreementCurve,
    raw: ByteArray,
): java.security.PublicKey {
    require(raw.isNotEmpty() && raw[0].toInt() == 0x04) { "ECDH public point must be uncompressed (0x04)" }
    val coord = curve.privateKeyBytes // coordinate width == scalar width for these curves
    require(raw.size == 1 + 2 * coord) { "bad ${curve.curveName} point length" }
    val x = beInt(raw, 1, coord)
    val y = beInt(raw, 1 + coord, coord)
    val spec = ECPublicKeySpec(ECPoint(x, y), ecParams(curve))
    return KeyFactory.getInstance("EC").generatePublic(spec)
}

/** Uncompressed SEC1 point `0x04 ‖ X ‖ Y` from an EC public key, zero-padded to coordinate width. */
private fun rawEcPoint(
    curve: KeyAgreementCurve,
    pub: java.security.PublicKey,
): ByteArray {
    val w = (pub as ECPublicKey).w
    val coord = curve.privateKeyBytes
    val out = ByteArray(1 + 2 * coord)
    out[0] = 0x04
    putFixedBe(w.affineX, out, 1, coord)
    putFixedBe(w.affineY, out, 1 + coord, coord)
    return out
}

private fun putFixedBe(
    value: BigInteger,
    dst: ByteArray,
    offset: Int,
    len: Int,
) {
    val be = value.toByteArray() // big-endian, may have a leading 0x00 sign byte or be shorter
    // strip a single leading sign byte if present and oversized
    var srcStart = 0
    var srcLen = be.size
    if (srcLen > len && be[0].toInt() == 0) {
        srcStart = 1
        srcLen -= 1
    }
    require(srcLen <= len) { "coordinate wider than field" }
    System.arraycopy(be, srcStart, dst, offset + (len - srcLen), srcLen)
}

// ---- glue -------------------------------------------------------------------

actual fun generateKeyPair(curve: KeyAgreementCurve): KeyAgreementKeyPair {
    requireSupported(curve)
    return when (curve) {
        KeyAgreementCurve.X25519 -> {
            val kpg = KeyPairGenerator.getInstance("XDH")
            kpg.initialize(NamedParameterSpec.X25519)
            val kp = kpg.generateKeyPair()
            val rawPub = rawX25519(kp.public)
            // Private scalar lives in a wiped SecureBuffer. JCA does not expose the raw XDH scalar
            // via a stable API, so we re-import the PKCS#8 octet string we'll need for agreement;
            // store the PKCS#8-extracted 32-byte scalar.
            val scalar = extractX25519Scalar(kp.private.encoded)
            val priv = secureBufferOf(curve.privateKeyBytes) { it.writeBytes(scalar) }
            scalar.fill(0)
            KeyAgreementKeyPair(curve, KeyAgreementPrivateKey(curve, priv), publicKeyOf(curve, rawPub))
        }
        else -> {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec(jcaCurveName(curve)))
            val kp = kpg.generateKeyPair()
            val rawPub = rawEcPoint(curve, kp.public)
            val s = (kp.private as java.security.interfaces.ECPrivateKey).s
            val scalar = ByteArray(curve.privateKeyBytes)
            putFixedBe(s, scalar, 0, curve.privateKeyBytes)
            val priv = secureBufferOf(curve.privateKeyBytes) { it.writeBytes(scalar) }
            scalar.fill(0)
            KeyAgreementKeyPair(curve, KeyAgreementPrivateKey(curve, priv), publicKeyOf(curve, rawPub))
        }
    }
}

/** Extracts the 32-byte X25519 scalar from a PKCS#8 PrivateKeyInfo encoding. */
private fun extractX25519Scalar(pkcs8: ByteArray): ByteArray {
    // The scalar is the last 32 bytes of the standard X25519 PKCS#8 encoding (inner OCTET STRING).
    val out = ByteArray(32)
    System.arraycopy(pkcs8, pkcs8.size - 32, out, 0, 32)
    return out
}

private fun publicKeyOf(
    curve: KeyAgreementCurve,
    raw: ByteArray,
): KeyAgreementPublicKey {
    val buf = BufferFactory.Default.allocate(raw.size)
    buf.writeBytes(raw)
    buf.resetForRead()
    return KeyAgreementPublicKey(curve, buf)
}

private inline fun secureBufferOf(
    size: Int,
    fill: (PlatformBuffer) -> Unit,
): PlatformBuffer {
    val buf = secureScratch.allocate(size)
    fill(buf)
    buf.resetForRead()
    return buf
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
    require(curve == peerPublicKey.curve) { "private/public key curve mismatch" }
    requireSupported(curve)

    val rawSecretBytes =
        try {
            rawAgree(curve, privateKey, peerPublicKey)
        } catch (e: InvalidPublicKey) {
            throw e
        } catch (e: GeneralSecurityException) {
            // Invalid-curve / off-curve / infinity points surface as a JCA exception → reject.
            throw InvalidPublicKey(curve.curveName)
        } catch (e: RuntimeException) {
            // Some JCA providers (and SunEC for certain malformed points) reject a bad public point
            // with an *unchecked* exception (ProviderException / IllegalArgumentException /
            // IllegalStateException) rather than a GeneralSecurityException. Map those to the same
            // uniform InvalidPublicKey so an off-curve probe can't be distinguished by exception
            // type and the documented rejection contract holds across providers. (IllegalArgument
            // from our own require()s is thrown before dispatch, so it never reaches here.)
            throw InvalidPublicKey(curve.curveName)
        }

    // Move the raw secret into a wiped SecureBuffer, then hand to the single audited KDF gate.
    val raw = secureScratch.allocate(curve.sharedSecretBytes)
    raw.writeBytes(rawSecretBytes)
    rawSecretBytes.fill(0)
    raw.resetForRead()
    return deriveFromRawSecret(curve, raw, info, length, salt, factory)
}

private fun rawAgree(
    curve: KeyAgreementCurve,
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
): ByteArray {
    val privScalar = privateKey.encoded.toBytes()
    val rawPub = peerPublicKey.encoded.toBytes()
    return try {
        when (curve) {
            KeyAgreementCurve.X25519 -> {
                val jcaPriv = x25519PrivateKeyFrom(privScalar)
                val jcaPub = x25519PublicKey(rawPub)
                val ka = JcaKeyAgreement.getInstance("XDH")
                ka.init(jcaPriv)
                ka.doPhase(jcaPub, true)
                ka.generateSecret()
            }
            else -> {
                val params = ecParams(curve)
                val d = BigInteger(1, privScalar)
                val jcaPriv =
                    KeyFactory
                        .getInstance("EC")
                        .generatePrivate(java.security.spec.ECPrivateKeySpec(d, params))
                val jcaPub = ecPublicKey(curve, rawPub)
                val ka = JcaKeyAgreement.getInstance("ECDH")
                ka.init(jcaPriv)
                ka.doPhase(jcaPub, true)
                ka.generateSecret()
            }
        }
    } finally {
        privScalar.fill(0)
    }
}

// Async wrappers: JVM/Android have a synchronous native KA, so just delegate.

actual suspend fun generateKeyPairAsync(curve: KeyAgreementCurve): KeyAgreementKeyPair = generateKeyPair(curve)

actual suspend fun deriveSharedSecretAsync(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
    info: ReadBuffer,
    length: Int,
    salt: ReadBuffer?,
    factory: BufferFactory,
): ReadBuffer = deriveSharedSecret(privateKey, peerPublicKey, info, length, salt, factory)
