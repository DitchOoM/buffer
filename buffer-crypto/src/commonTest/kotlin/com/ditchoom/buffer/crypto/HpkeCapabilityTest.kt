package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Capability gating for HPKE: for every suite, the [hpkeSupported] flag drives a `true`→works /
 * `false`→throws [UnsupportedOperationException] contract. A suite is supported iff its KEM curve
 * is usable here (sync native, or async WebCrypto on the web) **and** its AEAD is available
 * (ChaCha20-Poly1305 is absent from WebCrypto, so ChaCha suites are unsupported on the web). This
 * mirrors how the AEAD / key-agreement families gate their unavailable primitives — the unsupported
 * path is a tested error, not an assumption.
 */
class HpkeCapabilityTest {
    private val allSuites: List<HpkeSuite> =
        buildList {
            for (
            kem in
            listOf(
                HpkeKem.DhkemX25519HkdfSha256,
                HpkeKem.DhkemP256HkdfSha256,
                HpkeKem.DhkemP384HkdfSha384,
                HpkeKem.DhkemP521HkdfSha512,
            )
            ) {
                for (aead in listOf(HpkeAead.Aes128Gcm, HpkeAead.Aes256Gcm, HpkeAead.ChaCha20Poly1305)) {
                    add(HpkeSuite(kem, kem.kdf, aead))
                }
            }
        }

    @Test
    fun flagMatchesBehaviour() =
        runTest {
            for (suite in allSuites) {
                val supported = hpkeSupported(suite)
                // hpkeSupported reports sync support; on the web async covers extra curves, so also accept
                // the test-support notion (curve sync-or-web AND aead) as the authoritative expectation.
                val expectUsable = HpkeTestSupport.suiteSupported(suite)

                if (expectUsable) {
                    // A supported suite must complete a setup without throwing UnsupportedOperationException.
                    val recipient = hpkeGenerateKeyPair(suite.kem)
                    val sender = hpkeSetupBaseSender(suite, recipient.publicKey, ascii("cap"))
                    assertTrue(
                        sender.enc.remaining() == suite.kem.nEnc,
                        "${suite.kem.kemName}/${suite.aead.aeadName} enc length",
                    )
                    sender.context.close()
                    recipient.close()
                } else {
                    // An unsupported suite must throw UnsupportedOperationException, never silently degrade.
                    assertFailsWith<UnsupportedOperationException>(
                        "${suite.kem.kemName}/${suite.aead.aeadName} must throw when unsupported",
                    ) {
                        val recipient = hpkeGenerateKeyPair(suite.kem)
                        hpkeSetupBaseSender(suite, recipient.publicKey, ascii("cap"))
                    }
                }
                // The capability flag must agree with the observed behaviour for the AEAD dimension at least.
                if (!supported) {
                    // supported==false implies either the curve or the aead is unavailable on a sync path.
                    assertTrue(
                        !supportsSync(suite.kem.curve) ||
                            (
                                suite.aead == HpkeAead.ChaCha20Poly1305 && !chaChaPolyReachable
                            ) ||
                            (suite.aead != HpkeAead.ChaCha20Poly1305 && !aesGcmBlockingAvailable),
                        "hpkeSupported=false must reflect a genuinely-missing sync primitive",
                    )
                }
            }
        }

    @Test
    fun chaChaSuitesUnsupportedWhereChaChaIsUnavailable() =
        runTest {
            val suite = HpkeSuite(HpkeKem.DhkemX25519HkdfSha256, HpkeKdf.HkdfSha256, HpkeAead.ChaCha20Poly1305)
            if (!chaChaPolyReachable) {
                assertFailsWith<UnsupportedOperationException>("ChaCha suite must throw where ChaCha is absent") {
                    val recipient = hpkeGenerateKeyPair(suite.kem)
                    hpkeSetupBaseSender(suite, recipient.publicKey, ascii("x"))
                }
            }
        }
}
