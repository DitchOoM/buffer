package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlinx.coroutines.async
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
 * JS-only: the [HkdfKeyStore] over real IndexedDB (the `fake-indexeddb` shim under Node). A key imported
 * by one store instance reopens from another as a non-extractable `CryptoKey` and derives the same keys;
 * an occupied alias is refused, also when two imports race. Each test uses its own database name.
 */
class WebCryptoHkdfKeyStoreTest {
    @BeforeTest
    fun installIndexedDb() {
        js("require('fake-indexeddb/auto')")
        Unit
    }

    private val rootHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

    private fun store(db: String) = CryptoCapabilities.hkdfKeyStore(KeyStoreConfig(name = db))

    @Test
    fun theStoreIsTheNonExportableIndexedDbStore() =
        runTest {
            assertEquals(KeyCustody.NonExportable.Software, store("hkdf-tier").custody)
        }

    @Test
    fun aKeyReopenedFromAnotherStoreInstanceOpensWhatTheImportSealed() =
        runTest {
            val nonce = hexBuffer("0102030405060708090a0b0c")
            val imported = store("hkdf-reopen").importKey("root", hexBuffer(rootHex))
            assertEquals(KeyCustody.NonExportable.Software, imported.custody)
            val sealed = imported.deriveAesGcmKey(Salt.None, Info.Of(ascii("scope"))).sealWithNonce(nonce.slice(), ascii("kept"))
            val derived = imported.deriveBytes(Salt.None, Info.Of(ascii("bytes")), 32).toHex()
            imported.close()

            val reopened = assertNotNull(store("hkdf-reopen").load("root"))
            assertEquals(KeyCustody.NonExportable.Software, reopened.custody)
            assertEquals(32, reopened.sizeBytes)
            assertEquals(derived, reopened.deriveBytes(Salt.None, Info.Of(ascii("bytes")), 32).toHex())
            val opened = reopened.deriveAesGcmKey(Salt.None, Info.Of(ascii("scope"))).openWithNonce(nonce.slice(), sealed)
            assertEquals(ascii("kept").toHex(), opened.toHex())
            reopened.close()
        }

    @Test
    fun anOccupiedAliasIsRefusedAndTheStoredKeyIsUnchanged() =
        runTest {
            val s = store("hkdf-occupied")
            val first = s.importKey("root", hexBuffer(rootHex))
            val expected = first.deriveBytes(Salt.None, Info.None, 32).toHex()
            assertFailsWith<KeyStoreException.AliasOccupied> {
                s.importKey("root", hexBuffer("ff0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1eff"))
            }
            assertEquals(expected, assertNotNull(s.load("root")).deriveBytes(Salt.None, Info.None, 32).toHex())
        }

    @Test
    fun ofTwoRacingImportsExactlyOneIsStored() =
        runTest {
            val a = async { runCatching { store("hkdf-race").importKey("root", hexBuffer(rootHex)) } }
            val b = async { runCatching { store("hkdf-race").importKey("root", hexBuffer(rootHex)) } }
            val outcomes = listOf(a.await(), b.await())
            assertEquals(1, outcomes.count { it.isSuccess })
            assertTrue(outcomes.single { it.isFailure }.exceptionOrNull() is KeyStoreException.AliasOccupied)
        }

    @Test
    fun deleteRemovesTheKeyAndAliasesListsWhatIsStored() =
        runTest {
            val s = store("hkdf-delete")
            s.importKey("a", hexBuffer(rootHex)).close()
            s.importKey("b", hexBuffer(rootHex)).close()
            assertEquals(setOf("a", "b"), s.aliases())
            assertTrue(s.delete("a"))
            assertFalse(s.delete("a"))
            assertFalse(s.contains("a"))
            assertNull(s.load("a"))
            assertEquals(setOf("b"), s.aliases())
        }

    @Test
    fun aLoadOverAKeyStoreEntryInTheSameDatabaseIsAbsent() =
        runTest {
            CryptoCapabilities.keyStore(KeyStoreConfig(name = "hkdf-mixed")).getOrGenerateAesGcm("aes")
            assertNull(store("hkdf-mixed").load("aes"))
        }
}
