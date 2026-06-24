package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests the boundary transcoders in EcInterop: ECDSA signature DER <-> P1363. Three angles:
 *  - against the real native stack (sign -> DER -> P1363 -> DER -> verify round-trips),
 *  - pure inverse round-trips exercising the INTEGER padding edges (high bit set, leading zeros),
 *  - strict parsing rejects malformed DER / wrong-length P1363.
 */
class EcInteropTest {
    private val ecdsaSchemes =
        listOf(SignatureScheme.EcdsaP256, SignatureScheme.EcdsaP384, SignatureScheme.EcdsaP521)

    private fun field(scheme: SignatureScheme) =
        when (scheme) {
            SignatureScheme.EcdsaP256 -> 32
            SignatureScheme.EcdsaP384 -> 48
            SignatureScheme.EcdsaP521 -> 66
            SignatureScheme.Ed25519 -> error("n/a")
        }

    // --- against the real stack -------------------------------------------------

    @Test
    fun derToP1363ToDerVerifiesOnNativeStack() =
        runTest {
            // Native platforms produce DER; the transcoded-and-restored signature must still verify.
            if (ecdsaSignatureEncoding != EcdsaSignatureEncoding.Der) return@runTest
            for (scheme in ecdsaSchemes) {
                val ops = signatureBlockingOrNull(scheme) ?: continue
                ops.generateSigningKeyBlocking().use { key ->
                    val msg = hexBuffer("00112233445566778899aabbccddeeff")
                    val der = ops.signBlocking(key, msg)
                    val p1363 = ecdsaSignatureToP1363(scheme, der)
                    assertEquals(field(scheme) * 2, p1363.remaining(), "${scheme.schemeName} P1363 width")
                    val restoredDer = ecdsaSignatureToDer(scheme, p1363)
                    assertTrue(
                        ops.verifyBlocking(key.verifyKey, msg, restoredDer),
                        "${scheme.schemeName} der->p1363->der must still verify",
                    )
                }
            }
        }

    // --- pure inverse round-trips (no crypto) ----------------------------------

    @Test
    fun p1363ToDerToP1363IsIdentityAcrossPaddingEdges() {
        for (scheme in ecdsaSchemes) {
            val f = field(scheme)
            // r: high bit set in the top byte (DER must add a 0x00 sign byte).
            // s: several leading zero bytes (DER must strip them; P1363 must re-pad).
            val r = ByteArray(f) { if (it == 0) 0xF1.toByte() else (it and 0xFF).toByte() }
            val s = ByteArray(f) { if (it < 3) 0 else (0x80 or it).toByte() }
            val p1363 = bytes(r + s, BufferFactory.Default)
            val der = ecdsaSignatureToDer(scheme, p1363)
            val back = ecdsaSignatureToP1363(scheme, der)
            assertEquals((r + s).toHexString(), back.toHex(), "${scheme.schemeName} p1363->der->p1363 identity")
        }
    }

    @Test
    fun derToP1363ToDerReproducesCanonicalDer() {
        // A hand-built canonical P-256 DER signature (r high-bit-set so it carries a 0x00 pad).
        val derHex =
            "3046" +
                "022100" + "f1" + "1e".repeat(31) +
                "0221008e" + "2d".repeat(31)
        val der = hexBuffer(derHex)
        val p1363 = ecdsaSignatureToP1363(SignatureScheme.EcdsaP256, der)
        assertEquals(64, p1363.remaining())
        val restored = ecdsaSignatureToDer(SignatureScheme.EcdsaP256, p1363)
        assertEquals(derHex, restored.toHex(), "canonical DER reproduced")
    }

    // --- strict parsing ---------------------------------------------------------

    @Test
    fun rejectsTrailingBytesInDer() {
        val good = ecdsaSignatureToDer(SignatureScheme.EcdsaP256, bytes(ByteArray(64) { 1 }, BufferFactory.Default))
        val withTrailer = bytes(readBytes(good) + byteArrayOf(0), BufferFactory.Default)
        val e = assertFailsWith<EcEncodingException> { ecdsaSignatureToP1363(SignatureScheme.EcdsaP256, withTrailer) }
        assertEquals(EcEncodingError.MalformedDer, e.error)
    }

    @Test
    fun rejectsNonMinimalInteger() {
        // SEQUENCE(len 8) { INTEGER 00 00 01, INTEGER 01 } — superfluous leading zero in r.
        val der = hexBuffer("3008" + "0203000001" + "020101")
        val e = assertFailsWith<EcEncodingException> { ecdsaSignatureToP1363(SignatureScheme.EcdsaP256, der) }
        assertEquals(EcEncodingError.NonMinimalInteger, e.error)
    }

    @Test
    fun rejectsWrongLengthP1363() {
        val e =
            assertFailsWith<EcEncodingException> {
                ecdsaSignatureToDer(SignatureScheme.EcdsaP256, bytes(ByteArray(63) { 1 }, BufferFactory.Default))
            }
        assertEquals(EcEncodingError.WrongSignatureLength, e.error)
    }

    @Test
    fun rejectsEd25519() {
        val e =
            assertFailsWith<EcEncodingException> {
                ecdsaSignatureToP1363(SignatureScheme.Ed25519, bytes(ByteArray(64) { 1 }, BufferFactory.Default))
            }
        assertEquals(EcEncodingError.UnsupportedScheme, e.error)
    }

    // =========================================================================
    // EC key transcoders: PKCS#8, SPKI, point compression/decompression
    // OpenSSL-generated ground-truth vectors (prime256v1 / secp384r1 / secp521r1).
    // =========================================================================

    private val pCurves = listOf(KeyAgreementCurve.P256, KeyAgreementCurve.P384, KeyAgreementCurve.P521)

    private val p256Scalar = "72ccbb01e1b96e719b5cb1a658a3e0e04e2591878f7a65bbcff3841ca2ca076d"
    private val p256Uncompressed =
        "042e42a3c731b7aebd3aba7f84ba2f760a43f6d333cdbe86a1eb2a073f849c899f" +
            "0d0d0181ea2496e65eed149703fc84801fdb24e0a61beec11e3d202309ab52fe"
    private val p256X = "2e42a3c731b7aebd3aba7f84ba2f760a43f6d333cdbe86a1eb2a073f849c899f"
    private val p256Compressed = "02$p256X" // OpenSSL: Y is even here
    private val p256Spki =
        "3059301306072a8648ce3d020106082a8648ce3d03010703420004" +
            "2e42a3c731b7aebd3aba7f84ba2f760a43f6d333cdbe86a1eb2a073f849c899f" +
            "0d0d0181ea2496e65eed149703fc84801fdb24e0a61beec11e3d202309ab52fe"

    // OpenSSL's PKCS#8 carries the optional [1] publicKey; our strict parser must skip it.
    private val p256OpenSslPkcs8 =
        "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b020101" +
            "0420${p256Scalar}a144034200042e42a3c731b7aebd3aba7f84ba2f760a43f6d333" +
            "cdbe86a1eb2a073f849c899f0d0d0181ea2496e65eed149703fc84801fdb24e0a61bee" +
            "c11e3d202309ab52fe"

    private val x25519Scalar = "50943fe27f50a4d1a27a6bd0a706f3c687465aba0730a79fd991f518090fd853"

    private val p384Uncompressed =
        "046380d577a658a42b0690c494f99eeaf5153b6ec2fafe1afeb08c2b89ed02744b" +
            "4cef2cb746b7c3dc0730effe2dba75e9b3813aeaa07d7ea63e8428aa2d5f380173" +
            "a5914242eb39af2b2f541c3a21b2ef6a344e97a81f3fbc7ac7a718e8212d84"
    private val p384Compressed =
        "026380d577a658a42b0690c494f99eeaf5153b6ec2fafe1afeb08c2b89ed02744b" +
            "4cef2cb746b7c3dc0730effe2dba75e9"

    private val p521Uncompressed =
        "0401eb8571f0a9469b8025dac9ece18907e725b789d129b64dc17263cad04f363c" +
            "f0629c6450e38148ec6c0e23718a1f498be62a8899da428e3c57356220ff13979" +
            "4a3013e47e891d5aef0d0b35ab780dbaf69ba88ad52db211fca74efcbd3001c8e" +
            "4ae6558470d09dfcfb29e7a1c9d138b75883b2ba2e2a16a8574c87382268f18ca5dedc"
    private val p521Compressed =
        "0201eb8571f0a9469b8025dac9ece18907e725b789d129b64dc17263cad04f363c" +
            "f0629c6450e38148ec6c0e23718a1f498be62a8899da428e3c57356220ff13979" +
            "4a3"

    // --- PKCS#8 (#1) ------------------------------------------------------------

    @Test
    fun pkcs8EmitIsMinimalRfc5915AndOpenSslParseable() {
        // Byte-for-byte the minimal form (matches the JS scalarToPkcs8Hex wrapper; OpenSSL parses it).
        val expected = "3041020100301306072a8648ce3d020106082a8648ce3d030107042730250201010420$p256Scalar"
        assertEquals(expected, ecPrivateKeyToPkcs8(KeyAgreementCurve.P256, hexBuffer(p256Scalar)).toHex())
    }

    @Test
    fun pkcs8ParsesOpenSslFormWithTrailingPublicKey() {
        assertEquals(p256Scalar, pkcs8ToEcPrivateKey(KeyAgreementCurve.P256, hexBuffer(p256OpenSslPkcs8)).toHex())
    }

    @Test
    fun x25519Pkcs8EmitAndParseRfc8410() {
        val pkcs8 = "302e020100300506032b656e04220420$x25519Scalar"
        assertEquals(pkcs8, ecPrivateKeyToPkcs8(KeyAgreementCurve.X25519, hexBuffer(x25519Scalar)).toHex())
        assertEquals(x25519Scalar, pkcs8ToEcPrivateKey(KeyAgreementCurve.X25519, hexBuffer(pkcs8)).toHex())
    }

    @Test
    fun pkcs8RejectsWrongCurveOid() {
        // A P-256 PKCS#8 parsed as P-384 must fail on the namedCurve OID, not silently truncate.
        val e =
            assertFailsWith<EcEncodingException> {
                pkcs8ToEcPrivateKey(KeyAgreementCurve.P384, hexBuffer(p256OpenSslPkcs8))
            }
        assertEquals(EcEncodingError.CurveMismatch, e.error)
    }

    @Test
    fun pkcs8RejectsWrongScalarLength() {
        val e =
            assertFailsWith<EcEncodingException> {
                ecPrivateKeyToPkcs8(KeyAgreementCurve.P256, hexBuffer(p256Scalar.dropLast(2)))
            }
        assertEquals(EcEncodingError.WrongKeyLength, e.error)
    }

    // --- SPKI (#2) --------------------------------------------------------------

    @Test
    fun spkiEmitMatchesOpenSsl() {
        assertEquals(p256Spki, ecPublicKeyToSpki(KeyAgreementCurve.P256, hexBuffer(p256Uncompressed)).toHex())
    }

    @Test
    fun spkiParsesToUncompressedPoint() {
        assertEquals(p256Uncompressed, spkiToEcPublicKey(KeyAgreementCurve.P256, hexBuffer(p256Spki)).toHex())
    }

    @Test
    fun spkiRejectsWrongCurve() {
        val p384Spki = ecPublicKeyToSpki(KeyAgreementCurve.P384, hexBuffer(p384Uncompressed))
        val e =
            assertFailsWith<EcEncodingException> {
                spkiToEcPublicKey(KeyAgreementCurve.P256, p384Spki)
            }
        assertEquals(EcEncodingError.CurveMismatch, e.error)
    }

    // --- compression (#3) / decompression (#4) ----------------------------------

    @Test
    fun compressMatchesOpenSsl() {
        assertEquals(p256Compressed, ecPublicKeyCompress(KeyAgreementCurve.P256, hexBuffer(p256Uncompressed)).toHex())
    }

    @Test
    fun decompressMatchesOpenSslAcrossCurves() {
        assertEquals(p256Uncompressed, ecPublicKeyDecompress(KeyAgreementCurve.P256, hexBuffer(p256Compressed)).toHex())
        assertEquals(p384Uncompressed, ecPublicKeyDecompress(KeyAgreementCurve.P384, hexBuffer(p384Compressed)).toHex())
        assertEquals(p521Uncompressed, ecPublicKeyDecompress(KeyAgreementCurve.P521, hexBuffer(p521Compressed)).toHex())
    }

    @Test
    fun decompressSelectsYByParity() {
        val even = ecPublicKeyDecompress(KeyAgreementCurve.P256, hexBuffer("02$p256X")).toHex()
        val odd = ecPublicKeyDecompress(KeyAgreementCurve.P256, hexBuffer("03$p256X")).toHex()
        assertEquals(p256Uncompressed, even)
        assertNotEquals(even, odd, "the two parities are distinct points sharing one X")
        // Both are genuine on-curve points; re-compressing recovers the requested prefix.
        assertEquals("02$p256X", ecPublicKeyCompress(KeyAgreementCurve.P256, hexBuffer(even)).toHex())
        assertEquals("03$p256X", ecPublicKeyCompress(KeyAgreementCurve.P256, hexBuffer(odd)).toHex())
    }

    @Test
    fun decompressRejectsOffCurveX() {
        // x = 1 has no square root on P-256 (a deterministic non-residue), so it is not a point.
        val bad = "02${"00".repeat(31)}01"
        val e = assertFailsWith<EcEncodingException> { ecPublicKeyDecompress(KeyAgreementCurve.P256, hexBuffer(bad)) }
        assertEquals(EcEncodingError.PointNotOnCurve, e.error)
    }

    @Test
    fun decompressRejectsBadPrefixAndLength() {
        val badPrefix =
            assertFailsWith<EcEncodingException> {
                ecPublicKeyDecompress(KeyAgreementCurve.P256, hexBuffer("04$p256X"))
            }
        assertEquals(EcEncodingError.WrongKeyLength, badPrefix.error)
        val badLen =
            assertFailsWith<EcEncodingException> {
                ecPublicKeyDecompress(KeyAgreementCurve.P256, hexBuffer("02${"00".repeat(31)}"))
            }
        assertEquals(EcEncodingError.WrongKeyLength, badLen.error)
    }

    @Test
    fun pointOpsRejectX25519() {
        val raw = hexBuffer("04${"00".repeat(64)}")
        assertEquals(
            EcEncodingError.UnsupportedCurve,
            assertFailsWith<EcEncodingException> { ecPublicKeyCompress(KeyAgreementCurve.X25519, raw) }.error,
        )
        assertEquals(
            EcEncodingError.UnsupportedCurve,
            assertFailsWith<EcEncodingException> { ecPublicKeyToSpki(KeyAgreementCurve.X25519, raw) }.error,
        )
        assertEquals(
            EcEncodingError.UnsupportedCurve,
            assertFailsWith<EcEncodingException> {
                ecPublicKeyDecompress(KeyAgreementCurve.X25519, hexBuffer("02${"00".repeat(32)}"))
            }.error,
        )
    }

    // --- round-trips against the platform's own freshly-generated EC points ------

    @Test
    fun transcodersRoundTripAgainstGeneratedKeys() =
        runTest {
            for (curve in pCurves) {
                if (keyAgreementAsyncOrNull(curve) == null) continue // curve unavailable on this engine
                val kp = generateKeyPairAsync(curve)
                try {
                    val uncompressed = kp.publicKey.encoded
                    val uncompressedHex = uncompressed.toHex()

                    // #3 + #4: compress then decompress recovers the exact original point (both parities).
                    val compressed = ecPublicKeyCompress(curve, uncompressed)
                    assertEquals(1 + curve.privateKeyBytes, compressed.remaining(), "${curve.curveName} compressed width")
                    assertEquals(
                        uncompressedHex,
                        ecPublicKeyDecompress(curve, compressed).toHex(),
                        "${curve.curveName} compress->decompress identity",
                    )

                    // #2: SPKI emit then parse is the identity.
                    assertEquals(
                        uncompressedHex,
                        spkiToEcPublicKey(curve, ecPublicKeyToSpki(curve, uncompressed)).toHex(),
                        "${curve.curveName} SPKI round-trip",
                    )

                    // #1: PKCS#8 emit then parse recovers the raw scalar.
                    val scalar = kp.privateKey.exportEncoded()
                    assertEquals(
                        scalar.toHex(),
                        pkcs8ToEcPrivateKey(curve, ecPrivateKeyToPkcs8(curve, scalar)).toHex(),
                        "${curve.curveName} PKCS#8 round-trip",
                    )
                } finally {
                    kp.close()
                }
            }
        }

    private fun bytes(
        b: ByteArray,
        factory: BufferFactory,
    ): ReadBuffer {
        val out = factory.allocate(b.size)
        for (x in b) out.writeByte(x)
        out.resetForRead()
        return out
    }

    private fun readBytes(buffer: ReadBuffer): ByteArray {
        val start = buffer.position()
        return ByteArray(buffer.remaining()) { buffer.get(start + it) }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
