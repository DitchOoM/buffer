package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.fail

/**
 * Negative / tamper discipline for HPKE `open`. Every single-byte flip of the ciphertext+tag must
 * reject with the opaque [VerificationFailed]; a swapped or dropped AAD must reject; a wrong
 * recipient key must reject; a corrupted `enc` must reject (either as a rejected peer point
 * [InvalidPublicKey], or — if it still parses as a valid point — a [VerificationFailed] because the
 * decapsulated secret differs). No tampering ever yields plaintext.
 */
class HpkeTamperTest {
    private fun suite(): HpkeSuite? {
        val candidates =
            listOf(
                HpkeSuite(HpkeKem.DhkemX25519HkdfSha256, HpkeKdf.HkdfSha256, HpkeAead.Aes128Gcm),
                HpkeSuite(HpkeKem.DhkemP256HkdfSha256, HpkeKdf.HkdfSha256, HpkeAead.Aes128Gcm),
                HpkeSuite(HpkeKem.DhkemP521HkdfSha512, HpkeKdf.HkdfSha512, HpkeAead.Aes256Gcm),
            )
        return candidates.firstOrNull { HpkeTestSupport.suiteSupported(it) }
    }

    @Test
    fun everyCiphertextByteFlipRejected() =
        runTest {
            val suite = suite() ?: return@runTest
            val recipient = hpkeGenerateKeyPair(suite.kem)
            val info = ascii("tamper")
            val aad = ascii("aad")
            val sender = hpkeSetupBaseSender(suite, recipient.publicKey, info)
            val ct = sender.context.seal(ascii("the quick brown fox"), aad, BufferFactory.Default)

            val n = ct.remaining()
            for (i in 0 until n) {
                val mutated = flipByte(ct, i)
                val receiver = hpkeSetupBaseReceiver(suite, recipient.privateKey, sender.enc, info)
                assertFailsWith<VerificationFailed>("ciphertext byte $i flip must reject") {
                    receiver.open(mutated, ascii("aad"), BufferFactory.Default)
                }
                receiver.close()
            }
            recipient.close()
        }

    @Test
    fun aadSwapAndDropRejected() =
        runTest {
            val suite = suite() ?: return@runTest
            val recipient = hpkeGenerateKeyPair(suite.kem)
            val info = ascii("tamper-aad")
            val sender = hpkeSetupBaseSender(suite, recipient.publicKey, info)
            val ct = sender.context.seal(ascii("payload"), ascii("real-aad"), BufferFactory.Default)

            val r1 = hpkeSetupBaseReceiver(suite, recipient.privateKey, sender.enc, info)
            assertFailsWith<VerificationFailed>("swapped AAD must reject") {
                r1.open(ct, ascii("fake-aad"), BufferFactory.Default)
            }
            r1.close()

            val r2 = hpkeSetupBaseReceiver(suite, recipient.privateKey, sender.enc, info)
            assertFailsWith<VerificationFailed>("dropped AAD must reject") {
                r2.open(ct, null, BufferFactory.Default)
            }
            r2.close()
            recipient.close()
        }

    @Test
    fun wrongRecipientKeyRejected() =
        runTest {
            val suite = suite() ?: return@runTest
            val recipient = hpkeGenerateKeyPair(suite.kem)
            val wrong = hpkeGenerateKeyPair(suite.kem)
            val info = ascii("tamper-key")
            val sender = hpkeSetupBaseSender(suite, recipient.publicKey, info)
            val ct = sender.context.seal(ascii("payload"), null, BufferFactory.Default)

            val receiver = hpkeSetupBaseReceiver(suite, wrong.privateKey, sender.enc, info)
            assertFailsWith<VerificationFailed>("wrong recipient key must reject") {
                receiver.open(ct, null, BufferFactory.Default)
            }
            receiver.close()
            recipient.close()
            wrong.close()
        }

    @Test
    fun wrongInfoRejected() =
        runTest {
            val suite = suite() ?: return@runTest
            val recipient = hpkeGenerateKeyPair(suite.kem)
            val sender = hpkeSetupBaseSender(suite, recipient.publicKey, ascii("info-A"))
            val ct = sender.context.seal(ascii("payload"), null, BufferFactory.Default)

            // A receiver that uses a different `info` derives a different key schedule ⇒ tag fails.
            val receiver = hpkeSetupBaseReceiver(suite, recipient.privateKey, sender.enc, ascii("info-B"))
            assertFailsWith<VerificationFailed>("wrong info must reject") {
                receiver.open(ct, null, BufferFactory.Default)
            }
            receiver.close()
            recipient.close()
        }

    @Test
    fun corruptedEncRejected() =
        runTest {
            val suite = suite() ?: return@runTest
            val recipient = hpkeGenerateKeyPair(suite.kem)
            val info = ascii("tamper-enc")
            val sender = hpkeSetupBaseSender(suite, recipient.publicKey, info)
            val ct = sender.context.seal(ascii("payload"), null, BufferFactory.Default)

            // Flip a byte in the encapsulated key. Either the point is rejected (InvalidPublicKey) or it
            // still decodes to a valid point and the recovered secret differs (VerificationFailed). Both
            // are acceptable rejections; plaintext must never be produced.
            val encLen = sender.enc.remaining()
            var rejected = 0
            for (i in 0 until encLen) {
                val badEnc = flipByte(sender.enc, i)
                // Decap may reject the corrupted point at setup (InvalidPublicKey), or — if it still
                // decodes to a valid point — produce a different secret so `open` fails (VerificationFailed).
                val receiver =
                    try {
                        hpkeSetupBaseReceiver(suite, recipient.privateKey, badEnc, info)
                    } catch (_: InvalidPublicKey) {
                        rejected++
                        continue
                    }
                try {
                    receiver.open(ct, null, BufferFactory.Default)
                    fail("corrupted enc at byte $i must not yield plaintext")
                } catch (_: VerificationFailed) {
                    rejected++
                } catch (_: InvalidPublicKey) {
                    rejected++
                }
                receiver.close()
            }
            if (rejected == 0) fail("no enc corruption was exercised")
            recipient.close()
        }

    private fun flipByte(
        source: ReadBuffer,
        i: Int,
    ): ReadBuffer {
        val start = source.position()
        val n = source.remaining()
        val out = BufferFactory.Default.allocate(n)
        for (k in 0 until n) {
            val b = source.get(start + k)
            out.writeByte(if (k == i) (b.toInt() xor 0x01).toByte() else b)
        }
        out.resetForRead()
        return out
    }
}
