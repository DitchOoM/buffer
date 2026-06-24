package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.repeatedByte
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end HPKE round-trips with freshly generated keys, across every suite this module
 * implements (including DHKEM(P-384, HKDF-SHA384), which has no RFC 9180 Appendix A vector and so
 * is only exercised here) and all four modes. Proves seal→open recovers the plaintext, multiple
 * messages in a stream decrypt in order, and sender/receiver exports agree — on every platform that
 * supports the suite. A suite the platform lacks is skipped (its capability is asserted in
 * HpkeCapabilityTest).
 */
class HpkeRoundTripTest {
    private val allSuites: List<HpkeSuite> =
        listOf(
            HpkeSuite(HpkeKem.DhkemX25519HkdfSha256, HpkeKdf.HkdfSha256, HpkeAead.Aes128Gcm),
            HpkeSuite(HpkeKem.DhkemX25519HkdfSha256, HpkeKdf.HkdfSha256, HpkeAead.ChaCha20Poly1305),
            HpkeSuite(HpkeKem.DhkemP256HkdfSha256, HpkeKdf.HkdfSha256, HpkeAead.Aes128Gcm),
            HpkeSuite(HpkeKem.DhkemP256HkdfSha256, HpkeKdf.HkdfSha512, HpkeAead.Aes128Gcm),
            HpkeSuite(HpkeKem.DhkemP384HkdfSha384, HpkeKdf.HkdfSha384, HpkeAead.Aes256Gcm),
            HpkeSuite(HpkeKem.DhkemP521HkdfSha512, HpkeKdf.HkdfSha512, HpkeAead.Aes256Gcm),
        )

    @Test
    fun baseModeRoundTripAllSuites() =
        runTest {
            var ran = 0
            for (suite in allSuites) {
                if (!HpkeTestSupport.suiteSupported(suite)) continue
                ran++
                val recipient = hpkeGenerateKeyPair(suite.kem)
                val info = ascii("round-trip info")
                val pt = ascii("the quick brown fox jumps over the lazy dog")
                val aad = ascii("associated data")

                val sender = hpkeSetupBaseSender(suite, recipient.publicKey, info)
                val ct = sender.context.seal(pt, aad, BufferFactory.Default)

                val receiver = hpkeSetupBaseReceiver(suite, recipient.privateKey, sender.enc, info)
                val recovered = receiver.open(ct, aad, BufferFactory.Default)
                assertEquals(
                    pt.toHex(),
                    recovered.toHex(),
                    "${suite.kem.kemName}/${suite.aead.aeadName} base round-trip",
                )

                recipient.close()
            }
            assertTrue(ran > 0, "no HPKE suite was supported on this platform")
        }

    @Test
    fun singleShotApi() =
        runTest {
            val suite = firstSupported() ?: return@runTest
            val recipient = hpkeGenerateKeyPair(suite.kem)
            val info = ascii("single shot")
            val pt = ascii("payload bytes")
            val aad = ascii("aad")

            val sealed = hpkeSealBase(suite, recipient.publicKey, info, pt, aad)
            val opened = hpkeOpenBase(suite, recipient.privateKey, sealed.enc, info, sealed.ciphertext, aad)
            assertEquals(pt.toHex(), opened.toHex(), "single-shot round-trip")
            recipient.close()
        }

    @Test
    fun multiMessageStreamRoundTrip() =
        runTest {
            val suite = firstSupported() ?: return@runTest
            val recipient = hpkeGenerateKeyPair(suite.kem)
            val info = ascii("stream")

            val sender = hpkeSetupBaseSender(suite, recipient.publicKey, info)
            val receiver = hpkeSetupBaseReceiver(suite, recipient.privateKey, sender.enc, info)

            val messages = (0 until 8).map { ascii("message number $it") }
            val cts = messages.map { sender.context.seal(it, null, BufferFactory.Default) }
            for (i in messages.indices) {
                val recovered = receiver.open(cts[i], null, BufferFactory.Default)
                assertEquals(messages[i].toHex(), recovered.toHex(), "stream message $i")
            }
            recipient.close()
            sender.context.close()
            receiver.close()
        }

    @Test
    fun pskModeRoundTrip() =
        runTest {
            val suite = firstSupported() ?: return@runTest
            val recipient = hpkeGenerateKeyPair(suite.kem)
            val info = ascii("psk mode")
            val pt = ascii("secret message")
            val psk = HpkePsk.of(repeatedByte(0xAB, 32), ascii("psk-identity"))

            val sender = hpkeSetupPskSender(suite, recipient.publicKey, info, psk)
            val ct = sender.context.seal(pt, null, BufferFactory.Default)

            val psk2 = HpkePsk.of(repeatedByte(0xAB, 32), ascii("psk-identity"))
            val receiver = hpkeSetupPskReceiver(suite, recipient.privateKey, sender.enc, info, psk2)
            val recovered = receiver.open(ct, null, BufferFactory.Default)
            assertEquals(pt.toHex(), recovered.toHex(), "psk round-trip")
            recipient.close()
        }

    @Test
    fun authModeRoundTrip() =
        runTest {
            val suite = firstSupported() ?: return@runTest
            val recipient = hpkeGenerateKeyPair(suite.kem)
            val senderKp = hpkeGenerateKeyPair(suite.kem)
            val info = ascii("auth mode")
            val pt = ascii("authenticated message")

            val sender = hpkeSetupAuthSender(suite, recipient.publicKey, info, senderKp.privateKey)
            val ct = sender.context.seal(pt, null, BufferFactory.Default)

            val receiver = hpkeSetupAuthReceiver(suite, recipient.privateKey, sender.enc, info, senderKp.publicKey)
            val recovered = receiver.open(ct, null, BufferFactory.Default)
            assertEquals(pt.toHex(), recovered.toHex(), "auth round-trip")

            recipient.close()
            senderKp.close()
        }

    @Test
    fun authPskModeRoundTrip() =
        runTest {
            val suite = firstSupported() ?: return@runTest
            val recipient = hpkeGenerateKeyPair(suite.kem)
            val senderKp = hpkeGenerateKeyPair(suite.kem)
            val info = ascii("authpsk mode")
            val pt = ascii("authenticated psk message")
            val psk = HpkePsk.of(repeatedByte(0x5C, 32), ascii("id"))

            val sender = hpkeSetupAuthPskSender(suite, recipient.publicKey, info, psk, senderKp.privateKey)
            val ct = sender.context.seal(pt, null, BufferFactory.Default)

            val psk2 = HpkePsk.of(repeatedByte(0x5C, 32), ascii("id"))
            val receiver =
                hpkeSetupAuthPskReceiver(suite, recipient.privateKey, sender.enc, info, psk2, senderKp.publicKey)
            val recovered = receiver.open(ct, null, BufferFactory.Default)
            assertEquals(pt.toHex(), recovered.toHex(), "authpsk round-trip")

            recipient.close()
            senderKp.close()
        }

    @Test
    fun authModeRejectsWrongSender() =
        runTest {
            val suite = firstSupported() ?: return@runTest
            val recipient = hpkeGenerateKeyPair(suite.kem)
            val senderKp = hpkeGenerateKeyPair(suite.kem)
            val attackerKp = hpkeGenerateKeyPair(suite.kem)
            val info = ascii("auth")
            val pt = ascii("only the real sender can produce this")

            val sender = hpkeSetupAuthSender(suite, recipient.publicKey, info, senderKp.privateKey)
            val ct = sender.context.seal(pt, null, BufferFactory.Default)

            // A receiver that authenticates the WRONG sender public key must reject (the derived shared
            // secret differs, so the AEAD tag fails to verify).
            val receiver = hpkeSetupAuthReceiver(suite, recipient.privateKey, sender.enc, info, attackerKp.publicKey)
            try {
                receiver.open(ct, null, BufferFactory.Default)
                fail("auth-mode open with wrong sender key must reject")
            } catch (_: VerificationFailed) {
                // expected
            }
            recipient.close()
            senderKp.close()
            attackerKp.close()
        }

    @Test
    fun exportSecretsAgree() =
        runTest {
            val suite = firstSupported() ?: return@runTest
            val recipient = hpkeGenerateKeyPair(suite.kem)
            val info = ascii("export test")

            val sender = hpkeSetupBaseSender(suite, recipient.publicKey, info)
            val receiver = hpkeSetupBaseReceiver(suite, recipient.privateKey, sender.enc, info)

            val ctx = ascii("exporter context")
            val s = sender.context.export(ctx, 64, BufferFactory.Default)
            val r = receiver.export(ascii("exporter context"), 64, BufferFactory.Default)
            assertEquals(s.toHex(), r.toHex(), "exports must agree")
            assertEquals(64, s.remaining(), "export length")

            // A different context yields a different secret.
            val other = sender.context.export(ascii("other"), 64, BufferFactory.Default)
            assertTrue(s.toHex() != other.toHex(), "distinct export contexts must differ")

            recipient.close()
            sender.context.close()
            receiver.close()
        }

    private fun firstSupported(): HpkeSuite? = allSuites.firstOrNull { HpkeTestSupport.suiteSupported(it) }
}
