@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_CORRUPT
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_DENIED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_DUPLICATE
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_INPUT
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_INTERNAL
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_LOCKED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_NO_KEYCHAIN
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_RETRY
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_OK
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_classify
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_delete
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_get
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_put
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Keychain store's failure contract. The locked-device case cannot be
 * produced on a Mac test runner, so the OSStatus a locked device returns is fed through the shim's
 * own classifier; that a locked iPhone actually returns it is a device fact.
 */
class KeychainStoreHardeningTest {
    @Test
    fun aLockedKeychainIsDeviceLockedNotAGenericFailure() {
        assertEquals(BCKS_ERR_LOCKED, bcks_keychain_classify(ERR_SEC_INTERACTION_NOT_ALLOWED))
        assertIs<KeyStoreException.DeviceLocked>(
            keychainFailure(bcks_keychain_classify(ERR_SEC_INTERACTION_NOT_ALLOWED)),
        )
    }

    @Test
    fun eachKeychainStatusHasItsOwnArm() {
        assertEquals(BCKS_OK, bcks_keychain_classify(0))
        assertEquals(BCKS_ERR_INPUT, bcks_keychain_classify(ERR_SEC_ITEM_NOT_FOUND))
        assertEquals(BCKS_ERR_DENIED, bcks_keychain_classify(ERR_SEC_MISSING_ENTITLEMENT))
        assertEquals(BCKS_ERR_DENIED, bcks_keychain_classify(ERR_SEC_AUTH_FAILED))
        assertEquals(BCKS_ERR_CORRUPT, bcks_keychain_classify(ERR_SEC_DECODE))
        assertEquals(BCKS_ERR_DUPLICATE, bcks_keychain_classify(ERR_SEC_DUPLICATE_ITEM))
        assertEquals(BCKS_ERR_NO_KEYCHAIN, bcks_keychain_classify(ERR_SEC_NOT_AVAILABLE))
        assertEquals(BCKS_ERR_RETRY, bcks_keychain_classify(ERR_SEC_IO))
        assertEquals(BCKS_ERR_INTERNAL, bcks_keychain_classify(ERR_SEC_PARAM))

        assertIs<KeyStoreException.AccessDenied>(keychainFailure(BCKS_ERR_DENIED))
        assertIs<KeyStoreException.CorruptEntry>(keychainFailure(BCKS_ERR_CORRUPT))
        assertTrue(assertIs<KeyStoreException.StorageFailure>(keychainFailure(BCKS_ERR_RETRY)).retryable)
        assertTrue(!assertIs<KeyStoreException.StorageFailure>(keychainFailure(BCKS_ERR_NO_KEYCHAIN)).retryable)
        assertTrue(!assertIs<KeyStoreException.StorageFailure>(keychainFailure(BCKS_ERR_INTERNAL)).retryable)
    }

    @Test
    fun aLockedEnclaveIsDeviceLockedWhereverItRefuses() {
        assertIs<KeyStoreException.DeviceLocked>(enclaveGenerateFailure(BCKS_ERR_LOCKED))
        assertIs<HardwareKeyException.SecureElementUnavailable>(enclaveGenerateFailure(BCKS_ERR_INTERNAL))
        assertIs<Settlement.Unsettled<EnclaveProbe>>(enclaveProbeOf(BCKS_ERR_LOCKED), "asked again after the unlock")
    }

    @Test
    fun fourEnclaveStoresRacingToCreateOneAliasEndWithOneKey() =
        runTest {
            if (appleEnclaveProviderOrNull() == null) return@runTest
            val name = "bcks-race-${kotlin.random.Random.nextInt(Int.MAX_VALUE)}"
            try {
                val keys =
                    (0 until 4)
                        .map {
                            async(Dispatchers.Default) {
                                CryptoCapabilities
                                    .keyStore(
                                        KeyStoreConfig(name = name),
                                    ).getOrGenerateSigning("one", SignatureScheme.EcdsaP256)
                            }
                        }.awaitAll()
                val stored = assertNotNull(CryptoCapabilities.keyStore(KeyStoreConfig(name = name)).loadSigning("one"))
                val expected = stored.verifyKey.exportEncoded().toHexForTest()
                keys.forEach { key ->
                    assertEquals(expected, key.verifyKey.exportEncoded().toHexForTest(), "each racer holds it")
                }
            } finally {
                CryptoCapabilities.keyStore(KeyStoreConfig(name = name)).delete("one")
            }
        }

    @Test
    fun aPutNeverReplacesTheRecordAlreadyUnderAnAlias() {
        // The add-only write is what lets a racing get-or-generate keep the first key. A test executable on an iOS, tvOS
        // or watchOS simulator is not an app and carries no keychain entitlement, so the data protection keychain
        // refuses it with errSecMissingEntitlement; the macOS file keychain asks for none. There the refusal itself is
        // checked: every write is refused alike and nothing is stored.
        val service = "bcks-put-${kotlin.random.Random.nextInt(Int.MAX_VALUE)}"
        try {
            val first = putByte(service, FIRST)
            if (first == BCKS_ERR_DENIED) {
                assertEquals(BCKS_ERR_DENIED, putByte(service, SECOND), "a keychain this host cannot reach refuses every write")
                return
            }
            assertEquals(BCKS_OK, first)
            assertEquals(BCKS_ERR_DUPLICATE, putByte(service, SECOND))
            assertEquals(FIRST, readByte(service))
        } finally {
            bcks_keychain_delete(service, "one")
        }
    }

    private fun putByte(
        service: String,
        value: Byte,
    ): Int =
        memScoped {
            val one = allocArray<UByteVar>(1)
            one[0] = value.toUByte()
            bcks_keychain_put(service, "one", one, 1.convert())
        }

    private fun readByte(service: String): Byte =
        memScoped {
            val out = allocArray<UByteVar>(1)
            val len = alloc<platform.posix.size_tVar>()
            assertEquals(BCKS_OK, bcks_keychain_get(service, "one", out, 1.convert(), len.ptr))
            out[0].toByte()
        }

    @Test
    fun aRefusedEnclaveProbeIsTypedAndNeverASoftwareStore() =
        runTest {
            val unentitled =
                RetryingKeyStore { openAppleKeychainStore("bcks-probe-refused", EnclaveProbe.NotEntitled) }
            assertFailsWith<KeyStoreException.AccessDenied> { unentitled.aliases() }

            val refused = RetryingKeyStore { openAppleKeychainStore("bcks-probe-refused", EnclaveProbe.Refused) }
            assertTrue(assertFailsWith<KeyStoreException.StorageFailure> { refused.aliases() }.retryable)

            assertIs<Settlement.Settled<EnclaveProbe>>(enclaveProbeOf(BCKS_ERR_DENIED))
            assertIs<Settlement.Settled<EnclaveProbe>>(enclaveProbeOf(BCKS_OK))
            assertIs<Settlement.Unsettled<EnclaveProbe>>(
                enclaveProbeOf(BCKS_ERR_INTERNAL),
                "a refused probe is asked again",
            )
        }

    private companion object {
        const val ERR_SEC_INTERACTION_NOT_ALLOWED = -25308
        const val ERR_SEC_ITEM_NOT_FOUND = -25300
        const val ERR_SEC_MISSING_ENTITLEMENT = -34018
        const val ERR_SEC_AUTH_FAILED = -25293
        const val ERR_SEC_DECODE = -26275
        const val ERR_SEC_DUPLICATE_ITEM = -25299
        const val ERR_SEC_NOT_AVAILABLE = -25291
        const val ERR_SEC_PARAM = -50
        const val ERR_SEC_IO = -36
        const val BYTE_MASK = 0xFF
        const val FIRST: Byte = 1
        const val SECOND: Byte = 2
        const val HEX = 16
    }

    private fun com.ditchoom.buffer.ReadBuffer.toHexForTest(): String =
        (0 until remaining()).joinToString("") { i ->
            (get(position() + i).toInt() and BYTE_MASK).toString(HEX).padStart(2, '0')
        }
}
