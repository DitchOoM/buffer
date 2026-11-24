package com.ditchoom.buffer.crypto

import android.security.keystore.KeyProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import android.security.KeyStoreException as AndroidKeyStoreException

/**
 * The two Android Keystore decisions, off-device: which `KeyInfo` security levels count as hardware
 * custody, and which StrongBox refusals may fall back to the TEE. The device half — that the provider
 * reads these values from real keys and refusals — is in the instrumented suite.
 */
class KeystorePlacementTest {
    @Test
    fun onlyTeeAndStrongBoxAreHardware() {
        assertEquals(
            KeystorePlacement.SecureHardware(KeyCustody.NonExportable.Hardware(dedicatedSecureElement = true)),
            placementOfSecurityLevel(KeyProperties.SECURITY_LEVEL_STRONGBOX),
        )
        assertEquals(
            KeystorePlacement.SecureHardware(KeyCustody.NonExportable.Hardware(dedicatedSecureElement = false)),
            placementOfSecurityLevel(KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT),
        )
        listOf(
            KeyProperties.SECURITY_LEVEL_SOFTWARE,
            KeyProperties.SECURITY_LEVEL_UNKNOWN,
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE,
        ).forEach { level ->
            assertEquals(KeystorePlacement.NotSecureHardware, placementOfSecurityLevel(level), "level $level")
        }
    }

    @Test
    fun aLegacyKeyIsStrongBoxOnlyWhenTheStrongBoxAttemptMadeIt() {
        assertEquals(
            KeystorePlacement.SecureHardware(KeyCustody.NonExportable.Hardware(dedicatedSecureElement = true)),
            placementOfLegacyKeyInfo(insideSecureHardware = true, placedInStrongBox = true),
        )
        assertEquals(
            KeystorePlacement.SecureHardware(KeyCustody.NonExportable.Hardware(dedicatedSecureElement = false)),
            placementOfLegacyKeyInfo(insideSecureHardware = true, placedInStrongBox = false),
        )
        assertEquals(
            KeystorePlacement.NotSecureHardware,
            placementOfLegacyKeyInfo(insideSecureHardware = false, placedInStrongBox = true),
        )
    }

    @Test
    fun strongBoxAbsentOrUnsupportedFallsBackToTheTee() {
        assertEquals(
            StrongBoxRefusal.FallBackToTee,
            strongBoxRefusal(
                listOf(KeystoreFailureFact.StrongBoxUnavailable, coded(AndroidKeyStoreException.ERROR_UNIMPLEMENTED)),
            ),
        )
        listOf(
            AndroidKeyStoreException.ERROR_UNIMPLEMENTED,
            AndroidKeyStoreException.ERROR_INCORRECT_USAGE,
            AndroidKeyStoreException.ERROR_INTERNAL_SYSTEM_ERROR,
        ).forEach { code ->
            assertEquals(
                StrongBoxRefusal.FallBackToTee,
                strongBoxRefusal(listOf(KeystoreFailureFact.Wrapper, coded(code))),
                "code $code",
            )
        }
        assertEquals(StrongBoxRefusal.FallBackToTee, strongBoxRefusal(listOf(KeystoreFailureFact.SpecRejected)))
        assertEquals(
            StrongBoxRefusal.FallBackToTee,
            strongBoxRefusal(listOf(KeystoreFailureFact.Wrapper, KeystoreFailureFact.Unclassifiable)),
        )
    }

    @Test
    fun aBusyStrongBoxNeverSettlesForTheTee() {
        assertEquals(
            StrongBoxRefusal.Transient,
            strongBoxRefusal(
                listOf(
                    KeystoreFailureFact.Wrapper,
                    coded(AndroidKeyStoreException.ERROR_KEYMINT_FAILURE, transient = true),
                ),
            ),
        )
        assertEquals(
            StrongBoxRefusal.Transient,
            strongBoxRefusal(listOf(coded(AndroidKeyStoreException.ERROR_UNIMPLEMENTED, transient = true))),
            "a transient refusal is retried in StrongBox even when its code could mean unsupported",
        )
    }

    @Test
    fun anyOtherRefusalIsTerminal() {
        assertEquals(
            StrongBoxRefusal.Terminal,
            strongBoxRefusal(listOf(coded(AndroidKeyStoreException.ERROR_KEY_CORRUPTED))),
        )
        assertEquals(StrongBoxRefusal.Terminal, strongBoxRefusal(listOf(KeystoreFailureFact.Wrapper)))
        assertEquals(StrongBoxRefusal.Terminal, strongBoxRefusal(emptyList()))
    }

    private fun coded(
        code: Int,
        transient: Boolean = false,
    ) = KeystoreFailureFact.Coded(numericErrorCode = code, transientFailure = transient)
}
