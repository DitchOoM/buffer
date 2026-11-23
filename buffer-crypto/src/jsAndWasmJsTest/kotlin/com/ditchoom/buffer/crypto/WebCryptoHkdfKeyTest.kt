package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.ascii
import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * js/wasmJs: an imported [HkdfKey] is a non-extractable WebCrypto key, and so is everything derived from
 * it except [HkdfKey.deriveBytes]'s output. Agreement with raw-byte HKDF is [HkdfKeyTest]'s job; this
 * pins the custody a caller relies on.
 */
class WebCryptoHkdfKeyTest {
    private val rootHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

    @Test
    fun anImportedKeyAndItsDescendantsAreNonExportable() =
        runTest {
            val root = CryptoCapabilities.importHkdfKey(hexBuffer(rootHex))
            assertEquals(KeyCustody.NonExportable.Software, root.custody)
            val child = root.deriveHkdfKey(Salt.None, Info.Of(ascii("child")))
            assertEquals(KeyCustody.NonExportable.Software, child.custody)
            val grandchild = child.deriveHkdfKey(Salt.None, Info.Of(ascii("grandchild")))
            assertEquals(KeyCustody.NonExportable.Software, grandchild.custody)
            val aes = grandchild.deriveAesGcmKey(Salt.None, Info.None)
            assertEquals(KeyCustody.NonExportable.Software, aes.custody)
            listOf(aes, grandchild, child, root).forEach { it.close() }
        }
}
