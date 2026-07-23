package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.toByteArray
import java.io.ByteArrayInputStream
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/*
 * Cert-wrapped persistence support for [Tpm2Pkcs11KeyStore].
 *
 * The SunPKCS11 `KeyStore` refuses to list or address a private key that has no matching X.509
 * certificate object on the token, so every persisted key is wrapped in a minimal self-issued
 * certificate built here. The certificate is a **packaging requirement, not a trust assertion**:
 * nothing ever verifies it. Signing keys sign their own wrapper on the token; agreement keys cannot
 * sign (their PKCS#11 object is derive-capable, and key separation is deliberate), so their wrapper
 * carries a throwaway software signature over the real token public key.
 *
 * The wrapper doubles as the entry's kind record: the issuer CN is `bck.<kind>.<tag>` using the
 * shared persisted-key kind/tag constants from KeyStore.kt (KIND_SIGNING / KIND_AGREEMENT +
 * signingTag / agreementTag), so a reload can distinguish a signing entry from an agreement entry
 * without touching the private key — the same role the record kind byte plays in the Apple Keychain
 * store. The subject CN is the PKCS#11 label, which keeps entries legible to external tooling.
 *
 * `ByteArray` appears only at the unavoidable JCA seams (`PublicKey.getEncoded`, `Signature.sign`,
 * `CertificateFactory.generateCertificate`); the DER assembly itself stages through buffers.
 */

/** Builds the kind-tagged wrapper certificate over [publicKey], signed by [signTbs] (DER ECDSA out). */
internal fun buildWrapperCertificate(
    kind: Int,
    tag: Int,
    subjectCn: String,
    publicKey: PublicKey,
    signTbs: (ByteArray) -> ByteArray,
): X509Certificate {
    val issuer = rdnSequence(kindTagCn(kind, tag))
    val subject = rdnSequence(subjectCn)
    val tbs =
        der(
            DER_SEQUENCE,
            der(DER_CONTEXT_0, wrap(VERSION_V3_INTEGER)),
            positiveSerial(),
            wrap(ECDSA_SHA256_ALG_ID),
            issuer,
            wrap(VALIDITY),
            subject,
            wrap(publicKey.encoded),
        )
    val tbsBytes = tbs.toByteArray()
    val signature = signTbs(tbsBytes)
    val certificate =
        der(
            DER_SEQUENCE,
            wrap(tbsBytes),
            wrap(ECDSA_SHA256_ALG_ID),
            der(DER_BIT_STRING, wrap(ZERO_UNUSED_BITS), wrap(signature)),
        )
    return CertificateFactory
        .getInstance(X509_TYPE)
        .generateCertificate(ByteArrayInputStream(certificate.toByteArray())) as X509Certificate
}

/**
 * A wrapper signature from a throwaway software P-256 key, for keys that cannot sign their own
 * wrapper (agreement keys are derive-capable by design). The signer is discarded immediately;
 * nothing ever verifies the wrapper, so an unverifiable signature is exactly as good as any other.
 */
internal fun softwareWrapperSignature(tbs: ByteArray): ByteArray {
    val signer =
        java.security.KeyPairGenerator
            .getInstance(EC_ALGORITHM)
            .apply { initialize(java.security.spec.ECGenParameterSpec(SECP256R1_CURVE)) }
            .generateKeyPair()
    return java.security.Signature
        .getInstance(SHA256_ECDSA)
        .apply { initSign(signer.private) }
        .run {
            update(tbs)
            sign()
        }
}

/**
 * The (kind, tag) recorded in a wrapper certificate's issuer CN, or `null` when the certificate was
 * not written by this store (a foreign or pre-existing token object under our label namespace).
 */
internal fun storedKindOf(certificate: X509Certificate): Pair<Int, Int>? {
    val match = KIND_CN_REGEX.find(certificate.issuerX500Principal.getName(X500_RFC2253))
    val kind = match?.groupValues?.get(1)?.toIntOrNull()
    val tag = match?.groupValues?.get(2)?.toIntOrNull()
    return if (kind != null && tag != null) kind to tag else null
}

private fun kindTagCn(
    kind: Int,
    tag: Int,
): String = "$KIND_CN_PREFIX.$kind.$tag"

/** `Name` as a single-RDN `CN=<cn>` (UTF8String). */
private fun rdnSequence(cn: String): ReadBuffer =
    der(
        DER_SEQUENCE,
        der(
            DER_SET,
            der(DER_SEQUENCE, wrap(CN_OID), der(DER_UTF8_STRING, wrap(cn.encodeToByteArray()))),
        ),
    )

/**
 * A DER-minimal positive INTEGER serial from 8 CSPRNG bytes. The leading byte is forced into
 * `0x40..0x7F`, so the encoding is positive and minimal without a sign-padding octet.
 */
private fun positiveSerial(): ReadBuffer {
    val bytes = cryptoRandom(SERIAL_BYTES, BufferFactory.Default)
    val lead = bytes.readByte().toInt() and LEAD_CLAMP_MASK or LEAD_SET_BIT
    val out = BufferFactory.Default.allocate(SERIAL_BYTES)
    out.writeByte(lead.toByte())
    out.write(bytes)
    out.resetForRead()
    return der(DER_INTEGER, out)
}

/** One DER TLV: [tag], definite length, then every [parts] buffer's remaining bytes (consumed). */
private fun der(
    tag: Int,
    vararg parts: ReadBuffer,
): PlatformBuffer {
    val contentLength = parts.sumOf { it.remaining() }
    val lengthBytes =
        when {
            contentLength < LONG_FORM_THRESHOLD -> 1
            contentLength < ONE_BYTE_LENGTH_MAX -> 2
            else -> 3
        }
    val out = BufferFactory.Default.allocate(1 + lengthBytes + contentLength)
    out.writeByte(tag.toByte())
    when (lengthBytes) {
        1 -> out.writeByte(contentLength.toByte())
        2 -> {
            out.writeByte(LONG_FORM_ONE_BYTE.toByte())
            out.writeByte(contentLength.toByte())
        }
        else -> {
            out.writeByte(LONG_FORM_TWO_BYTES.toByte())
            out.writeShort(contentLength.toShort())
        }
    }
    parts.forEach { out.write(it) }
    out.resetForRead()
    return out
}

private fun wrap(bytes: ByteArray): ReadBuffer = BufferFactory.Default.wrap(bytes)

// TLV pieces small enough that the byte-level encodings are clearer than an arc/time encoder.
private const val DER_SEQUENCE = 0x30
private const val DER_SET = 0x31
private const val DER_INTEGER = 0x02
private const val DER_BIT_STRING = 0x03
private const val DER_UTF8_STRING = 0x0C
private const val DER_CONTEXT_0 = 0xA0
private const val LONG_FORM_THRESHOLD = 0x80
private const val ONE_BYTE_LENGTH_MAX = 0x100
private const val LONG_FORM_ONE_BYTE = 0x81
private const val LONG_FORM_TWO_BYTES = 0x82
private const val SERIAL_BYTES = 8
private const val LEAD_CLAMP_MASK = 0x3F
private const val LEAD_SET_BIT = 0x40
private const val X509_TYPE = "X.509"
private const val SHA256_ECDSA = "SHA256withECDSA"
private const val X500_RFC2253 = "RFC2253"
private const val KIND_CN_PREFIX = "bck"
private val KIND_CN_REGEX = Regex("""CN=bck\.(\d+)\.(\d+)""")

/** `INTEGER 2` — X.509 v3. */
private val VERSION_V3_INTEGER = byteArrayOf(0x02, 0x01, 0x02)

/** OID 2.5.4.3 (`commonName`), pre-encoded. */
private val CN_OID = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)

/** `AlgorithmIdentifier` for ecdsa-with-SHA256 (1.2.840.10045.4.3.2, no parameters). */
private val ECDSA_SHA256_ALG_ID =
    byteArrayOf(
        0x30,
        0x0A,
        0x06,
        0x08,
        0x2A,
        0x86.toByte(),
        0x48,
        0xCE.toByte(),
        0x3D,
        0x04,
        0x03,
        0x02,
    )

/** `Validity`: notBefore 2025-01-01 UTCTime, notAfter 9999-12-31 GeneralizedTime (RFC 5280 no-expiry). */
private val VALIDITY =
    byteArrayOf(0x30, 0x20, 0x17) + byteArrayOf(0x0D) + "250101000000Z".encodeToByteArray() +
        byteArrayOf(0x18, 0x0F) + "99991231235959Z".encodeToByteArray()

private val ZERO_UNUSED_BITS = byteArrayOf(0x00)
