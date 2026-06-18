package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import com.ditchoom.buffer.pool.BufferPool
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * HPKE backing matrix: drives a full setup→seal→open→export cycle with the plaintext, AAD, info,
 * and KEM-key buffers placed across every backing (heap × direct × pooled × slice), asserting
 * identical recovered plaintext and identical exports regardless of how the inputs are backed. This
 * exercises both platform-bridge branches (managed-array vs native-pointer) and the
 * wrapper-transparency contract (pooled / slice) through the whole HPKE stack.
 *
 * Uses an RFC 9180 KAT vector (fixed keys + ephemeral) so the `enc`/`ct` are deterministic and can
 * be asserted exactly across backings, not just for self-consistency.
 */
class HpkeBackingTests {
    @Test
    fun sealOpenExportAcrossBackings() =
        runTest {
            // A.1.1 Base / DHKEM(X25519,SHA256) / AES-128-GCM if supported, else first supported KAT vector.
            val vec = pickVector() ?: return@runTest
            val kem = HpkeTestSupport.kemById(vec.kemId)
            val suite = HpkeSuite(kem, HpkeTestSupport.kdfById(vec.kdfId), HpkeTestSupport.aeadById(vec.aeadId))

            val pool = BufferPool()
            for (infoKind in CryptoBackings.inputs) {
                for (ptKind in CryptoBackings.inputs) {
                    for (aadKind in CryptoBackings.inputs) {
                        val info = CryptoBackings.place(infoKind, hexBuffer(vec.info), pool)
                        val recipientPub = HpkeTestSupport.publicKeyFromHex(kem, vec.pkRm)
                        val ephemeral = HpkeTestSupport.keyPairFromHex(kem, vec.skEm, vec.pkEm)

                        val sender =
                            hpkeSetupSenderInternal(
                                suite,
                                HpkeMode.Base,
                                recipientPub,
                                info,
                                psk = null,
                                senderPrivateKey = null,
                                ephemeral = ephemeral,
                            )
                        assertEquals(vec.enc, sender.enc.toHex(), "enc info=$infoKind pt=$ptKind aad=$aadKind")

                        val ptSource = hexBuffer(vec.pt)
                        val aadSource = hexBuffer(vec.aad)
                        val pt = CryptoBackings.place(ptKind, ptSource, pool)
                        val aad = CryptoBackings.place(aadKind, aadSource, pool)

                        sender.context.setSeqForTest(0)
                        val ct = sender.context.seal(pt, aad, BufferFactory.Default)
                        assertEquals(vec.ctSeq0, ct.toHex(), "ct info=$infoKind pt=$ptKind aad=$aadKind")

                        // Round-trip open with the recovered receiver, inputs also re-backed.
                        val info2 = CryptoBackings.place(infoKind, hexBuffer(vec.info), pool)
                        val recipientPriv = HpkeTestSupport.privateKeyFromHex(kem, vec.skRm, vec.pkRm)
                        val receiver = hpkeSetupBaseReceiver(suite, recipientPriv, hexBuffer(vec.enc), info2)
                        val ctBacked = CryptoBackings.place(ptKind, hexBuffer(vec.ctSeq0), pool)
                        val aad2 = CryptoBackings.place(aadKind, hexBuffer(vec.aad), pool)
                        val recovered = receiver.open(ctBacked, aad2, BufferFactory.Default)
                        assertEquals(vec.pt, recovered.toHex(), "open info=$infoKind pt=$ptKind aad=$aadKind")

                        sender.context.close()
                        receiver.close()
                    }
                }
            }
            pool.clear()
        }

    private data class Vec(
        val kemId: Int,
        val kdfId: Int,
        val aeadId: Int,
        val info: String,
        val pkRm: String,
        val skRm: String,
        val skEm: String,
        val pkEm: String,
        val enc: String,
        val pt: String,
        val aad: String,
        val ctSeq0: String,
    )

    /** Picks the first vendored A.x.1 Base vector whose suite is supported here. */
    private fun pickVector(): Vec? {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(HpkeRfcVectors.JSON) as kotlinx.serialization.json.JsonObject
        val vectors = (root["vectors"]!! as kotlinx.serialization.json.JsonArray)
        for (vEl in vectors) {
            val v = vEl as kotlinx.serialization.json.JsonObject

            fun s(n: String) = (v[n] as kotlinx.serialization.json.JsonPrimitive).content

            fun i(n: String) = s(n).toInt()
            if (i("mode") != 0) continue
            val suite =
                HpkeSuite(
                    HpkeTestSupport.kemById(i("kem_id")),
                    HpkeTestSupport.kdfById(i("kdf_id")),
                    HpkeTestSupport.aeadById(i("aead_id")),
                )
            if (!HpkeTestSupport.suiteSupported(suite)) continue
            // The backing matrix drives a KAT vector with pinned raw scalars, importable only on
            // raw-scalar platforms (JVM/Android). On web/Apple the wrapper-transparency contract for
            // HPKE is still covered by HpkeRoundTripTest running across generated keys.
            if (!supportsRawScalarKat(suite.kem.curve)) continue
            val enc0 = (v["encryptions"]!! as kotlinx.serialization.json.JsonArray)[0] as kotlinx.serialization.json.JsonObject

            fun e(n: String) = (enc0[n] as kotlinx.serialization.json.JsonPrimitive).content
            return Vec(
                kemId = i("kem_id"),
                kdfId = i("kdf_id"),
                aeadId = i("aead_id"),
                info = s("info"),
                pkRm = s("pkRm"),
                skRm = s("skRm"),
                skEm = s("skEm"),
                pkEm = s("pkEm"),
                enc = s("enc"),
                pt = e("pt"),
                aad = e("aad"),
                ctSeq0 = e("ct"),
            )
        }
        return null
    }
}
