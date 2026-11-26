@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_AUTH
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_CANCELED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_CORRUPT
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_DENIED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_DUPLICATE
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_INPUT
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_INTERACTION
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_INTERNAL
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_LOCKED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_NO_KEYCHAIN
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_RETRY
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_OK
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_classify
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * How each keychain OSStatus is classified, through the shim's own classifier: the statuses a locked device or a
 * refused prompt return cannot be produced on a Mac test runner, so they are fed in directly. That a locked iPhone
 * returns them is a device fact.
 */
class KeychainStatusClassificationTest {
    @Test
    fun aLockedKeychainIsDeviceLockedNotAGenericFailure() {
        assertIs<KeyStoreException.DeviceLocked>(keychainFailure(BCKS_ERR_LOCKED))
    }

    /**
     * On macOS `errSecInteractionNotAllowed` is a keychain that needs a prompt this process may not show (a locked
     * login keychain, an access prompt, no UI session), not a locked device. The iOS arm, where it is the locked
     * device, is compiled for the iOS targets and is not reachable from this host.
     */
    @OptIn(ExperimentalNativeApi::class)
    @Test
    fun onMacOsANoPromptKeychainIsInteractionRequiredNotDeviceLocked() {
        if (Platform.osFamily != OsFamily.MACOSX) return
        assertEquals(BCKS_ERR_INTERACTION, bcks_keychain_classify(ERR_SEC_INTERACTION_NOT_ALLOWED))
        assertIs<KeyStoreException.InteractionRequired>(keychainFailure(BCKS_ERR_INTERACTION))
    }

    /**
     * On macOS a cancelled or failed status is the user's answer to the file keychain's access prompt. On iOS, watchOS
     * and tvOS the store's items carry no access control, so no prompt was shown, and the status is a keychain failure;
     * that arm is compiled for those targets and not reachable from this host.
     */
    @OptIn(ExperimentalNativeApi::class)
    @Test
    fun aDeclinedOrFailedPromptIsDeclinedOnlyWhereThereIsAPrompt() {
        assertIs<KeyStoreException.AuthenticationDeclined>(keychainFailure(BCKS_ERR_CANCELED))
        assertIs<KeyStoreException.AuthenticationDeclined>(keychainFailure(BCKS_ERR_AUTH))
        if (Platform.osFamily == OsFamily.MACOSX) {
            assertEquals(BCKS_ERR_CANCELED, bcks_keychain_classify(ERR_SEC_USER_CANCELED))
            assertEquals(BCKS_ERR_AUTH, bcks_keychain_classify(ERR_SEC_AUTH_FAILED))
        } else {
            assertEquals(BCKS_ERR_INTERNAL, bcks_keychain_classify(ERR_SEC_USER_CANCELED))
            assertEquals(BCKS_ERR_INTERNAL, bcks_keychain_classify(ERR_SEC_AUTH_FAILED))
        }
    }

    @Test
    fun aKeychainNotAvailableIsWorthRetrying() {
        assertEquals(BCKS_ERR_NO_KEYCHAIN, bcks_keychain_classify(ERR_SEC_NOT_AVAILABLE))
        assertTrue(
            assertIs<KeyStoreException.StorageFailure>(keychainFailure(BCKS_ERR_NO_KEYCHAIN)).retryable,
            "early in boot the keychain may not be up yet",
        )
    }

    @Test
    fun eachKeychainStatusHasItsOwnArm() {
        assertEquals(BCKS_OK, bcks_keychain_classify(0))
        assertEquals(BCKS_ERR_INPUT, bcks_keychain_classify(ERR_SEC_ITEM_NOT_FOUND))
        assertEquals(BCKS_ERR_DENIED, bcks_keychain_classify(ERR_SEC_MISSING_ENTITLEMENT))
        assertEquals(BCKS_ERR_DENIED, bcks_keychain_classify(ERR_SEC_NO_ACCESS_FOR_ITEM))
        assertEquals(BCKS_ERR_CORRUPT, bcks_keychain_classify(ERR_SEC_DECODE))
        assertEquals(BCKS_ERR_DUPLICATE, bcks_keychain_classify(ERR_SEC_DUPLICATE_ITEM))
        assertEquals(BCKS_ERR_RETRY, bcks_keychain_classify(ERR_SEC_IO))
        assertEquals(BCKS_ERR_INTERNAL, bcks_keychain_classify(ERR_SEC_PARAM))

        assertIs<KeyStoreException.AccessDenied>(keychainFailure(BCKS_ERR_DENIED))
        assertIs<KeyStoreException.CorruptEntry>(keychainFailure(BCKS_ERR_CORRUPT))
        assertTrue(assertIs<KeyStoreException.StorageFailure>(keychainFailure(BCKS_ERR_RETRY)).retryable)
        assertTrue(!assertIs<KeyStoreException.StorageFailure>(keychainFailure(BCKS_ERR_INTERNAL)).retryable)
    }

    @Test
    fun aProtectionClassMoveThatCannotSucceedSettles() {
        assertEquals(MigrationStep.Settled, migrationStep(BCKS_OK))
        listOf(BCKS_ERR_DENIED, BCKS_ERR_INTERNAL, BCKS_ERR_CORRUPT).forEach { status ->
            assertEquals(MigrationStep.Settled, migrationStep(status), "status $status is not asked again")
        }
        listOf(BCKS_ERR_LOCKED, BCKS_ERR_RETRY, BCKS_ERR_INTERACTION, BCKS_ERR_NO_KEYCHAIN).forEach { status ->
            assertEquals(MigrationStep.TryAgain, migrationStep(status), "status $status may pass")
        }
    }

    private companion object {
        const val ERR_SEC_INTERACTION_NOT_ALLOWED = -25308
        const val ERR_SEC_ITEM_NOT_FOUND = -25300
        const val ERR_SEC_MISSING_ENTITLEMENT = -34018
        const val ERR_SEC_AUTH_FAILED = -25293
        const val ERR_SEC_USER_CANCELED = -128
        const val ERR_SEC_NO_ACCESS_FOR_ITEM = -25243
        const val ERR_SEC_DECODE = -26275
        const val ERR_SEC_DUPLICATE_ITEM = -25299
        const val ERR_SEC_NOT_AVAILABLE = -25291
        const val ERR_SEC_PARAM = -50
        const val ERR_SEC_IO = -36
    }
}
