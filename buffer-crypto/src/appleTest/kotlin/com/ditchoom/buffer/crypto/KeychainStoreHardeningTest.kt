@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_CORRUPT
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_DENIED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_INPUT
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_INTERNAL
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_LOCKED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_RETRY
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_OK
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_classify
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
        assertEquals(BCKS_ERR_RETRY, bcks_keychain_classify(ERR_SEC_DUPLICATE_ITEM))
        assertEquals(BCKS_ERR_RETRY, bcks_keychain_classify(ERR_SEC_NOT_AVAILABLE))
        assertEquals(BCKS_ERR_INTERNAL, bcks_keychain_classify(ERR_SEC_PARAM))

        assertIs<KeyStoreException.AccessDenied>(keychainFailure(BCKS_ERR_DENIED))
        assertIs<KeyStoreException.CorruptEntry>(keychainFailure(BCKS_ERR_CORRUPT))
        assertTrue(assertIs<KeyStoreException.StorageFailure>(keychainFailure(BCKS_ERR_RETRY)).retryable)
        assertTrue(!assertIs<KeyStoreException.StorageFailure>(keychainFailure(BCKS_ERR_INTERNAL)).retryable)
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
    }
}
