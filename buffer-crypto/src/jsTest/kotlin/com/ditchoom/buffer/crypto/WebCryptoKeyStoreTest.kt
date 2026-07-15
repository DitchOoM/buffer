package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JS-only: the persistent [WebCryptoKeyStore] backed by real IndexedDB (a `fake-indexeddb` shim under
 * Node). Asserts the platform `keyStore()` resolves to the **non-exportable** WebCrypto/IndexedDB
 * store (not the software fallback), that a non-extractable `CryptoKey` durably survives being dropped
 * and reloaded from IDB, and the alias-mismatch / delete / aliases contract. Each test uses a distinct
 * database name so state does not leak between tests within the Node process.
 */
class WebCryptoKeyStoreTest {
    @BeforeTest
    fun installIndexedDb() {
        js("require('fake-indexeddb/auto')")
        Unit
    }

    private fun store(db: String) = CryptoCapabilities.keyStore(KeyStoreConfig(name = db))

    @Test
    fun keyStoreResolvesToTheNonExportableWebCryptoStore() =
        runTest {
            val s = store("db-tier")
            // If the shim didn't install, keyStore() would fall back to ExportableSoftware — this asserts
            // we are actually exercising the IndexedDB-backed non-exportable store.
            assertEquals(CustodyTier.NonExportableSoftware, s.custodyFor(ProtectedKeyAlgorithm.EcdsaP256).tier)
        }

    @Test
    fun signingKeyPersistsNonExportablyAcrossReload() =
        runTest {
            val gen = store("db-sign").getOrGenerateSigning("identity", SignatureScheme.EcdsaP256)
            assertEquals(KeyCustody.NonExportable.Software, gen.custody)
            val spki = gen.verifyKey.exportSpki().toHex()
            gen.close() // drop the live CryptoKey handle; the IndexedDB copy must survive

            val reloaded = assertNotNull(store("db-sign").loadSigning("identity"))
            assertEquals(KeyCustody.NonExportable.Software, reloaded.custody)
            assertEquals(spki, reloaded.verifyKey.exportSpki().toHex())
            val msg = hexBuffer("00112233445566778899aabbccddeeff")
            assertTrue(verifyAsync(reloaded.verifyKey, msg, signAsync(reloaded, msg)))

            // Idempotent get-or-generate returns the same key.
            assertEquals(
                spki,
                store("db-sign")
                    .getOrGenerateSigning("identity", SignatureScheme.EcdsaP256)
                    .verifyKey
                    .exportSpki()
                    .toHex(),
            )
        }

    @Test
    fun aesKeyReloadsAndOpensWhatItSealed() =
        runTest {
            val original = store("db-aes").getOrGenerateAesGcm("data")
            assertEquals(KeyCustody.NonExportable.Software, original.custody)
            val plaintext = "6f70656e2d6d652d6166746572"
            val sealed =
                when (val w = CryptoCapabilities.aesGcm) {
                    is Aead.Blocking -> w.ops
                    is Aead.AsyncOnly -> w.ops
                }.seal(original, hexBuffer(plaintext))

            val reloaded = assertNotNull(store("db-aes").loadAesGcm("data"))
            val opened =
                when (val w = CryptoCapabilities.aesGcm) {
                    is Aead.Blocking -> w.ops
                    is Aead.AsyncOnly -> w.ops
                }.open(sealed, reloaded)
            assertEquals(plaintext, opened.toHex())
        }

    @Test
    fun keyAgreementReloadsToTheSameSharedSecret() =
        runTest {
            val ours = store("db-kex").getOrGenerateKeyAgreement("kex", KeyAgreementCurve.P256)
            val peer = generateKeyPairAsync(KeyAgreementCurve.P256)
            val info = hexBuffer("6b6578")
            val reloaded = assertNotNull(store("db-kex").loadKeyAgreement("kex"))
            val a = deriveSharedSecretAsync(reloaded.privateKey, peer.publicKey, info, 32).toHex()
            val b = deriveSharedSecretAsync(peer.privateKey, ours.publicKey, info, 32).toHex()
            assertEquals(a, b)
        }

    @Test
    fun aliasMismatchAndDeleteAndAliases() =
        runTest {
            val s = store("db-life")
            s.getOrGenerateSigning("a", SignatureScheme.EcdsaP256)
            s.getOrGenerateAesGcm("b")
            assertEquals(setOf("a", "b"), s.aliases())

            assertFailsWith<KeyStoreException.AliasMismatch> {
                s.getOrGenerateSigning("b", SignatureScheme.EcdsaP256) // "b" is an AES alias
            }
            assertNull(s.loadKeyAgreement("a")) // wrong kind → null, not error

            assertTrue(s.delete("a"))
            assertFalse(s.contains("a"))
            assertEquals(setOf("b"), s.aliases())
        }
}
