package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import com.ditchoom.buffer.crypto.CryptoTestVectors.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

private const val DERIVED_BYTES = 32

/**
 * wasmJs: the [HkdfKeyStore]'s own IndexedDB glue (`@JsFun` externals). Node has no IndexedDB, so these
 * run only under the browser test task; under Node they return without asserting.
 */
class WasmHkdfKeyStoreTest {
    private val rootHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

    private fun store(db: String) = CryptoCapabilities.hkdfKeyStore(KeyStoreConfig(name = db))

    @Test
    fun aKeyReopensNonExportablyAndAnOccupiedAliasIsRefused() =
        runTest {
            if (!webCryptoIndexedDbAvailable) return@runTest
            val s = store("wasm-hkdf")
            s.delete("root")
            val imported = s.importKey("root", hexBuffer(rootHex))
            val expected = imported.deriveBytes(Salt.None, Info.Of(ascii("x")), DERIVED_BYTES).toHex()
            imported.close()
            assertFailsWith<KeyStoreException.AliasOccupied> { s.importKey("root", hexBuffer(rootHex)) }

            val reopened = assertNotNull(store("wasm-hkdf").load("root"))
            assertEquals(KeyCustody.NonExportable.Software, reopened.custody)
            assertEquals(expected, reopened.deriveBytes(Salt.None, Info.Of(ascii("x")), DERIVED_BYTES).toHex())
            reopened.close()
            s.delete("root")
        }
}
