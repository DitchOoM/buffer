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

// Raw X25519 public-key / scalar width in bytes.
private const val X25519_RAW_BYTES = 32

// SEC1 prefix byte marking an uncompressed elliptic-curve point (0x04 ‖ X ‖ Y).
private const val SEC1_UNCOMPRESSED_POINT: Int = 0x04

private fun probe(block: () -> Unit): Boolean =
    try {
        block()
        true
    } catch (_: Throwable) {
        false
    }

private val supportsSyncX25519: Boolean =
    probe {
        KeyPairGenerator.getInstance("XDH").initialize(NamedParameterSpec.X25519)
        JcaKeyAgreement.getInstance("XDH")
    }

private fun probeEc(curve: String): Boolean =
    probe {
        KeyPairGenerator.getInstance("EC").initialize(ECGenParameterSpec(curve))
        JcaKeyAgreement.getInstance("ECDH")
    }

private val supportsSyncEcdhP256: Boolean = probeEc("secp256r1")
private val supportsSyncEcdhP384: Boolean = probeEc("secp384r1")
private val supportsSyncEcdhP521: Boolean = probeEc("secp521r1")

/** Whether [curve] has a synchronous JCA path on this runtime (X25519 needs XDH; Android 14+). */
private fun jvmSupportsSync(curve: KeyAgreementCurve): Boolean =
    when (curve) {
        KeyAgreementCurve.X25519 -> supportsSyncX25519
        KeyAgreementCurve.P256 -> supportsSyncEcdhP256
        KeyAgreementCurve.P384 -> supportsSyncEcdhP384
        KeyAgreementCurve.P521 -> supportsSyncEcdhP521
    }

/** JVM/Android have a synchronous JCA KA for every curve the provider supports; else [Unavailable]. */
actual fun CryptoCapabilities.keyAgreement(curve: KeyAgreementCurve): KeyAgreementSupport =
    if (jvmSupportsSync(curve)) {
        KeyAgreementSupport.Blocking(KeyAgreementBlockingOpsImpl(curve))
    } else {
        KeyAgreementSupport.Unavailable
    }

private fun jcaCurveName(curve: KeyAgreementCurve): String =
    when (curve) {
        KeyAgreementCurve.P256 -> "secp256r1"
        KeyAgreementCurve.P384 -> "secp384r1"
        KeyAgreementCurve.P521 -> "secp521r1"
        KeyAgreementCurve.X25519 -> error("X25519 is not an EC named curve")
    }

private fun requireSupported(curve: KeyAgreementCurve) {
    if (!jvmSupportsSync(curve)) {
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
    val spki = ByteArray(x25519SpkiPrefix.size + X25519_RAW_BYTES)
    System.arraycopy(x25519SpkiPrefix, 0, spki, 0, x25519SpkiPrefix.size)
    System.arraycopy(raw, 0, spki, x25519SpkiPrefix.size, X25519_RAW_BYTES)
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
    val der = ByteArray(pkcs8Prefix.size + X25519_RAW_BYTES)
    System.arraycopy(pkcs8Prefix, 0, der, 0, pkcs8Prefix.size)
    System.arraycopy(scalar, 0, der, pkcs8Prefix.size, X25519_RAW_BYTES)
    return KeyFactory.getInstance("XDH").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(der))
}

/** Little-endian 32-byte raw u from an XEC public key's BigInteger u-coordinate. */
private fun rawX25519(pub: java.security.PublicKey): ByteArray {
    val u = (pub as XECPublicKey).u
    val be = u.toByteArray() // big-endian, possibly with sign byte / shorter than 32
    val raw = ByteArray(X25519_RAW_BYTES)
    // copy big-endian magnitude into the low bytes, then reverse to little-endian
    var src = be.size - 1
    var dst = 0
    while (src >= 0 && dst < X25519_RAW_BYTES) {
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
    require(raw.isNotEmpty() && raw[0].toInt() == SEC1_UNCOMPRESSED_POINT) {
        "ECDH public point must be uncompressed (0x04)"
    }
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
    out[0] = SEC1_UNCOMPRESSED_POINT.toByte()
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

internal actual fun generateKeyPairPlatform(curve: KeyAgreementCurve): KeyAgreementKeyPair {
    requireSupported(curve)
    return when (curve) {
        KeyAgreementCurve.X25519 -> {
            val kpg = KeyPairGenerator.getInstance("XDH")
            kpg.initialize(NamedParameterSpec.X25519)
            val kp = kpg.generateKeyPair()
            val rawPub = rawX25519(kp.public)
            // Private scalar lives in a wiped SecureBuffer. JCA does not expose the raw XDH scalar
            // via a stable API, so we re-import the PKCS#8 octet string we'll need for agreement;
            // store the PKCS#8-extracted 32-byte scalar. Both staging copies (the PKCS#8 encoding
            // and the extracted scalar) are wiped once the scalar reaches the SecureBuffer.
            val pkcs8 = kp.private.encoded
            val scalar =
                try {
                    extractX25519Scalar(pkcs8)
                } finally {
                    pkcs8.fill(0)
                }
            val priv = secureBufferOf(curve.privateKeyBytes) { it.writeBytes(scalar) }
            scalar.fill(0)
            keyAgreementKeyPairOf(curve, keyAgreementPrivateKeyOf(curve, priv), publicKeyOf(curve, rawPub))
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
            keyAgreementKeyPairOf(curve, keyAgreementPrivateKeyOf(curve, priv), publicKeyOf(curve, rawPub))
        }
    }
}

// DER tags/length constants for the minimal PKCS#8 walk below.
private const val DER_SEQUENCE = 0x30
private const val DER_INTEGER = 0x02
private const val DER_OCTET_STRING = 0x04
private const val DER_LONG_FORM = 0x80
private const val DER_LEN_MASK = 0x7F
private const val UBYTE_MASK = 0xFF

/**
 * Extracts the 32-byte X25519 scalar from a PKCS#8 `PrivateKeyInfo` encoding by walking the DER
 * structure (RFC 5958 / RFC 8410): `SEQUENCE { version INTEGER, privateKeyAlgorithm SEQUENCE,
 * privateKey OCTET STRING { CurvePrivateKey ::= OCTET STRING (the raw scalar) } }`. Locating the
 * nested OCTET STRING — rather than assuming the scalar is the trailing 32 bytes — stays correct
 * for providers that append optional `attributes` or `publicKey` fields to the encoding.
 */
private fun extractX25519Scalar(pkcs8: ByteArray): ByteArray {
    var pos = 0

    fun u8(): Int {
        check(pos < pkcs8.size) { "truncated PKCS#8 PrivateKeyInfo" }
        return pkcs8[pos++].toInt() and UBYTE_MASK
    }

    // DER length: short form, or long form with 1..2 length octets (a PrivateKeyInfo is tiny).
    fun length(): Int {
        val first = u8()
        if (first < DER_LONG_FORM) return first
        val numBytes = first and DER_LEN_MASK
        check(numBytes in 1..2) { "unsupported DER length form in PKCS#8 PrivateKeyInfo" }
        var len = 0
        repeat(numBytes) { len = (len shl Byte.SIZE_BITS) or u8() }
        return len
    }

    // Reads a TLV header, checks the tag and that the value fits, and returns the value length
    // with [pos] left at the value's first byte.
    fun expectTag(tag: Int): Int {
        check(u8() == tag) { "unexpected DER tag in PKCS#8 PrivateKeyInfo" }
        val len = length()
        check(len <= pkcs8.size - pos) { "truncated PKCS#8 PrivateKeyInfo" }
        return len
    }

    // Skips a whole TLV: reads the header, then jumps over the value.
    fun skipTag(tag: Int) {
        val len = expectTag(tag)
        pos += len
    }

    expectTag(DER_SEQUENCE) // PrivateKeyInfo — descend
    skipTag(DER_INTEGER) // version
    skipTag(DER_SEQUENCE) // privateKeyAlgorithm (id-X25519)
    expectTag(DER_OCTET_STRING) // privateKey — descend
    val scalarLen = expectTag(DER_OCTET_STRING) // CurvePrivateKey ::= OCTET STRING (RFC 8410 §7)
    check(scalarLen == X25519_RAW_BYTES) { "X25519 scalar must be $X25519_RAW_BYTES bytes, was $scalarLen" }
    return pkcs8.copyOfRange(pos, pos + X25519_RAW_BYTES)
}

private fun publicKeyOf(
    curve: KeyAgreementCurve,
    raw: ByteArray,
): KeyAgreementPublicKey {
    val buf = BufferFactory.Default.allocate(raw.size)
    buf.writeBytes(raw)
    buf.resetForRead()
    return KeyAgreementPublicKey.of(curve, buf)
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

// Provider rejections are mapped to a single uniform InvalidPublicKey on purpose: the cause is
// intentionally dropped and RuntimeException is intentionally caught so a bad/off-curve point
// cannot be distinguished by exception type or cause (oracle avoidance across JCA providers).
@Suppress("SwallowedException", "TooGenericExceptionCaught", "ThrowsCount")
internal actual fun deriveSharedSecretPlatform(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
    info: ReadBuffer?,
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
            throw InvalidPublicKey(curve)
        } catch (e: RuntimeException) {
            // Some JCA providers (and SunEC for certain malformed points) reject a bad public point
            // with an *unchecked* exception (ProviderException / IllegalArgumentException /
            // IllegalStateException) rather than a GeneralSecurityException. Map those to the same
            // uniform InvalidPublicKey so an off-curve probe can't be distinguished by exception
            // type and the documented rejection contract holds across providers. (IllegalArgument
            // from our own require()s is thrown before dispatch, so it never reaches here.)
            throw InvalidPublicKey(curve)
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
    val privScalar = privateKey.requireInMemoryMaterial().toBytes()
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

/**
 * Raw DH secret seam for HPKE/DHKEM. Performs the native agreement (with the same provider
 * public-key validation as [deriveSharedSecret]) and hands back the raw secret in a wiped
 * SecureBuffer — without the KDF step the public path applies.
 *
 * Same uniform-rejection contract as [deriveSharedSecret]: the cause is dropped and RuntimeException
 * is caught on purpose so no provider exception type/cause leaks a public-point oracle.
 */
@Suppress("SwallowedException", "TooGenericExceptionCaught", "ThrowsCount")
internal actual suspend fun dhRawSecret(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
): PlatformBuffer {
    val curve = privateKey.curve
    require(curve == peerPublicKey.curve) { "private/public key curve mismatch" }
    requireSupported(curve)

    val rawSecretBytes =
        try {
            rawAgree(curve, privateKey, peerPublicKey)
        } catch (e: InvalidPublicKey) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw InvalidPublicKey(curve)
        } catch (e: RuntimeException) {
            // Unchecked provider rejections (ProviderException / IllegalState / IllegalArgument from
            // the provider) map to the same uniform InvalidPublicKey — no exception-type oracle.
            throw InvalidPublicKey(curve)
        }

    val raw = secureScratch.allocate(curve.sharedSecretBytes)
    raw.writeBytes(rawSecretBytes)
    rawSecretBytes.fill(0)
    raw.resetForRead()
    return validateRawSecret(curve, raw)
}

// Async wrappers: JVM/Android have a synchronous native KA, so just delegate.

internal actual suspend fun generateKeyPairAsyncPlatform(curve: KeyAgreementCurve): KeyAgreementKeyPair = generateKeyPairPlatform(curve)

internal actual suspend fun deriveSharedSecretAsyncPlatform(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
    info: ReadBuffer?,
    length: Int,
    salt: ReadBuffer?,
    factory: BufferFactory,
): ReadBuffer = deriveSharedSecretPlatform(privateKey, peerPublicKey, info, length, salt, factory)
