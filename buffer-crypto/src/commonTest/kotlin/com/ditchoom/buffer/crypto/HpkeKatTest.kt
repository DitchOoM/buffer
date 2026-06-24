package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RFC 9180 Appendix A known-answer tests. For each vendored vector this drives a full HPKE
 * sender setup with the pinned ephemeral key (so `enc` and the per-message ciphertexts are
 * reproducible), then drives the matching receiver setup and verifies:
 *
 *  - the encapsulated key `enc` matches the vector,
 *  - each seq-0/1/2/4 `seal` produces the vector's `ct`, and `open` recovers the plaintext,
 *  - the receiver `open` round-trips,
 *  - each `export` matches the vector's `exported_value`.
 *
 * This transitively pins the entire chain (DHKEM `ExtractAndExpand`, the key schedule's `key` /
 * `base_nonce` / `exporter_secret`, and the per-message nonce derivation) against the RFC, since a
 * single wrong byte anywhere changes the ciphertext or export.
 */
class HpkeKatTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun rfc9180AppendixAVectors() =
        runTest {
            val root = json.parseToJsonElement(HpkeRfcVectors.JSON).jsonObject
            val vectors = root["vectors"]!!.jsonArray
            var ran = 0
            for (vEl in vectors) {
                val v = vEl.jsonObject
                ran += runVector(v)
            }
            // On JVM/Android (raw-scalar import) at least one vector must run; on web/Apple the KAT is
            // intentionally skipped (raw-scalar private keys are not importable there — see runVector),
            // and HpkeRoundTripTest provides the cross-platform coverage.
            val anyRawScalarCurve =
                listOf(
                    KeyAgreementCurve.X25519,
                    KeyAgreementCurve.P256,
                    KeyAgreementCurve.P384,
                    KeyAgreementCurve.P521,
                ).any { supportsRawScalarKat(it) }
            if (anyRawScalarCurve) {
                assertTrue(ran > 0, "expected at least one HPKE KAT vector to run on this platform")
            }
        }

    /** Runs one vector; returns 1 if it executed, 0 if skipped (suite unsupported on this platform). */
    private suspend fun runVector(v: JsonObject): Int {
        val kem = HpkeTestSupport.kemById(v.int("kem_id"))
        val kdf = HpkeTestSupport.kdfById(v.int("kdf_id"))
        val aead = HpkeTestSupport.aeadById(v.int("aead_id"))
        val suite = HpkeSuite(kem, kdf, aead)
        if (!HpkeTestSupport.suiteSupported(suite)) return 0
        // The KAT pins raw private scalars (skEm/skRm/skSm); only platforms whose private-key
        // encoding is the raw scalar (JCA — JVM/Android) can import them. Apple/web use a different
        // private encoding and get their HPKE coverage from HpkeRoundTripTest (generated keys).
        if (!supportsRawScalarKat(kem.curve)) return 0

        val mode = HpkeTestSupport.modeByValue(v.int("mode"))
        val name = v.str("name")
        val info = hexBuffer(v.str("info"))

        // Recipient and ephemeral keys (pinned).
        val recipientPriv = HpkeTestSupport.privateKeyFromHex(kem, v.str("skRm"), v.str("pkRm"))
        val recipientPub = HpkeTestSupport.publicKeyFromHex(kem, v.str("pkRm"))
        val ephemeral = HpkeTestSupport.keyPairFromHex(kem, v.str("skEm"), v.str("pkEm"))

        // PSK / sender-auth params per mode.
        val psk =
            if (mode == HpkeMode.Psk || mode == HpkeMode.AuthPsk) {
                HpkePsk.of(hexBuffer(v.str("psk")), hexBuffer(v.str("psk_id")))
            } else {
                null
            }
        val senderPriv =
            if (mode == HpkeMode.Auth || mode == HpkeMode.AuthPsk) {
                HpkeTestSupport.privateKeyFromHex(kem, v.str("skSm"), v.str("pkSm"))
            } else {
                null
            }
        val senderPub =
            if (mode == HpkeMode.Auth || mode == HpkeMode.AuthPsk) {
                HpkeTestSupport.publicKeyFromHex(kem, v.str("pkSm"))
            } else {
                null
            }

        // --- Sender setup with the pinned ephemeral ---
        val senderSetup =
            hpkeSetupSenderInternal(suite, mode, recipientPub, info, psk, senderPriv, ephemeral)
        assertEquals(v.str("enc"), senderSetup.enc.toHex(), "$name: enc")

        // --- Receiver setup ---
        val receiver =
            when (mode) {
                HpkeMode.Base -> hpkeSetupBaseReceiver(suite, recipientPriv, hexBuffer(v.str("enc")), info)
                HpkeMode.Psk -> hpkeSetupPskReceiver(suite, recipientPriv, hexBuffer(v.str("enc")), info, psk!!)
                HpkeMode.Auth -> hpkeSetupAuthReceiver(suite, recipientPriv, hexBuffer(v.str("enc")), info, senderPub!!)
                HpkeMode.AuthPsk ->
                    hpkeSetupAuthPskReceiver(suite, recipientPriv, hexBuffer(v.str("enc")), info, psk!!, senderPub!!)
            }

        // --- Per-message encryptions (seq 0,1,2,4 in vector order) ---
        val encryptions = v["encryptions"]!!.jsonArray
        for (eEl in encryptions) {
            val e = eEl.jsonObject
            val seq = e.int("seq").toLong()
            // The RFC's encryption vectors have gaps (0,1,2,4,...); pin both contexts to the vector's
            // sequence number before each op (the only place the counter is ever set explicitly).
            senderSetup.context.setSeqForTest(seq)
            receiver.setSeqForTest(seq)

            val aad = if (e.str("aad").isEmpty()) null else hexBuffer(e.str("aad"))
            val pt = hexBuffer(e.str("pt"))
            val ct = senderSetup.context.seal(pt, aad, BufferFactory.Default)
            assertEquals(e.str("ct"), ct.toHex(), "$name: ct seq=$seq")

            val aad2 = if (e.str("aad").isEmpty()) null else hexBuffer(e.str("aad"))
            val recovered = receiver.open(hexBuffer(e.str("ct")), aad2, BufferFactory.Default)
            assertEquals(e.str("pt"), recovered.toHex(), "$name: open seq=$seq")
        }

        // --- Exports (from both contexts; they share exporter_secret) ---
        val exports = v["exports"]!!.jsonArray
        for (xEl in exports) {
            val x = xEl.jsonObject
            val ctx = if (x.str("context").isEmpty()) emptyBuffer() else hexBuffer(x.str("context"))
            val l = x.int("L")
            val exported = senderSetup.context.export(ctx, l, BufferFactory.Default)
            assertEquals(x.str("value"), exported.toHex(), "$name: export ctx=${x.str("context")}")

            val ctx2 = if (x.str("context").isEmpty()) emptyBuffer() else hexBuffer(x.str("context"))
            val exportedR = receiver.export(ctx2, l, BufferFactory.Default)
            assertEquals(x.str("value"), exportedR.toHex(), "$name: export(receiver) ctx=${x.str("context")}")
        }

        senderSetup.context.close()
        receiver.close()
        return 1
    }

    private fun emptyBuffer(): ReadBuffer = hexBuffer("")

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content ?: error("missing field '$key'")

    private fun JsonObject.int(name: String): Int = this[name]?.jsonPrimitive?.int ?: error("missing int '$name'")
}
