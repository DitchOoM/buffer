package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `VerifyKey` / `KeyAgreementPublicKey` public-key export: raw import encoding and X.509 SPKI DER.
 * Ground-truth SPKI vectors are OpenSSL-generated (shared with [EcInteropTest]); these tests assert
 * the sealed-key `export*` accessors emit exactly the same bytes as the underlying `EcInterop`
 * encoders and that `exportEncoded` round-trips back through the import factories.
 */
class PublicKeyExportTest {
    // OpenSSL ground truth (prime256v1 / secp384r1 / secp521r1 / Ed25519 / X25519).
    private val p256Uncompressed =
        "042e42a3c731b7aebd3aba7f84ba2f760a43f6d333cdbe86a1eb2a073f849c899f" +
            "0d0d0181ea2496e65eed149703fc84801fdb24e0a61beec11e3d202309ab52fe"
    private val p256Spki =
        "3059301306072a8648ce3d020106082a8648ce3d03010703420004" +
            "2e42a3c731b7aebd3aba7f84ba2f760a43f6d333cdbe86a1eb2a073f849c899f" +
            "0d0d0181ea2496e65eed149703fc84801fdb24e0a61beec11e3d202309ab52fe"
    private val p384Uncompressed =
        "046380d577a658a42b0690c494f99eeaf5153b6ec2fafe1afeb08c2b89ed02744b" +
            "4cef2cb746b7c3dc0730effe2dba75e9b3813aeaa07d7ea63e8428aa2d5f380173" +
            "a5914242eb39af2b2f541c3a21b2ef6a344e97a81f3fbc7ac7a718e8212d84"
    private val p521Uncompressed =
        "0401eb8571f0a9469b8025dac9ece18907e725b789d129b64dc17263cad04f363c" +
            "f0629c6450e38148ec6c0e23718a1f498be62a8899da428e3c57356220ff13979" +
            "4a3013e47e891d5aef0d0b35ab780dbaf69ba88ad52db211fca74efcbd3001c8e" +
            "4ae6558470d09dfcfb29e7a1c9d138b75883b2ba2e2a16a8574c87382268f18ca5dedc"
    private val ed25519Public = "e55551cf661a77bcfe7fb50284931f6437335738c84c842f48a6fc9c827fce7a"
    private val ed25519Spki = "302a300506032b6570032100$ed25519Public"
    private val x25519Public = "1df2ea465caf9d4e21ba8f4025a60d3ac4012bc67e012003dcf59268c84cc868"
    private val x25519Spki = "302a300506032b656e032100$x25519Public"

    // --- VerifyKey ------------------------------------------------------------------

    @Test
    fun ecdsaVerifyKeyExportsPointAndSpki() {
        val cases =
            listOf(
                Triple(VerifyKey.ecdsaP256(hexBuffer(p256Uncompressed)), p256Uncompressed, p256Spki),
                Triple(VerifyKey.ecdsaP384(hexBuffer(p384Uncompressed)), p384Uncompressed, null),
                Triple(VerifyKey.ecdsaP521(hexBuffer(p521Uncompressed)), p521Uncompressed, null),
            )
        for ((key, point, spki) in cases) {
            assertEquals(point, key.exportEncoded().toHex(), "${key.scheme.schemeName} exportEncoded")
            // exportEncoded round-trips back through the import factory (re-export is identical).
            assertEquals(point, verifyKeyOf(key.scheme, key.exportEncoded()).exportEncoded().toHex())
            if (spki != null) assertEquals(spki, key.exportSpki().toHex(), "${key.scheme.schemeName} exportSpki")
            // SPKI parses back to the same point on every curve.
            val curve =
                when (key.scheme) {
                    SignatureScheme.EcdsaP256 -> KeyAgreementCurve.P256
                    SignatureScheme.EcdsaP384 -> KeyAgreementCurve.P384
                    SignatureScheme.EcdsaP521 -> KeyAgreementCurve.P521
                    SignatureScheme.Ed25519 -> error("n/a")
                }
            assertEquals(point, spkiToEcPublicKey(curve, key.exportSpki()).toHex())
        }
    }

    @Test
    fun ed25519VerifyKeyExportsRawAndSpki() {
        val key = VerifyKey.ed25519(hexBuffer(ed25519Public))
        assertEquals(ed25519Public, key.exportEncoded().toHex())
        assertEquals(ed25519Spki, key.exportSpki().toHex())
        assertEquals(ed25519Public, spkiToEd25519PublicKey(key.exportSpki()).toHex())
    }

    @Test
    fun verifyKeyExportEncodedIsIndependentCopyAndRepeatable() {
        val key = VerifyKey.ecdsaP256(hexBuffer(p256Uncompressed))
        // Two exports are equal (the accessor is non-destructive on the shared material).
        assertEquals(key.exportEncoded().toHex(), key.exportEncoded().toHex())
        assertEquals(p256Spki, key.exportSpki().toHex())
        assertEquals(p256Spki, key.exportSpki().toHex())
    }

    // --- KeyAgreementPublicKey ------------------------------------------------------

    @Test
    fun keyAgreementPublicKeyExportsSpki() {
        assertEquals(
            p256Spki,
            KeyAgreementPublicKey.of(KeyAgreementCurve.P256, hexBuffer(p256Uncompressed)).exportSpki().toHex(),
        )
        assertEquals(
            x25519Spki,
            KeyAgreementPublicKey.of(KeyAgreementCurve.X25519, hexBuffer(x25519Public)).exportSpki().toHex(),
        )
    }
}
