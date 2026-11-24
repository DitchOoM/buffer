package com.ditchoom.buffer.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import android.security.KeyStoreException as Ks
import android.security.keystore.KeyProperties as Level
import com.ditchoom.buffer.crypto.SecureElementFallbackReason as Reason

/**
 * The Android Keystore decisions, off-device: which `KeyInfo` security levels count as hardware custody, what happens
 * to a key outside it, and how a refused StrongBox generation falls back to the TEE. The device half — that the
 * provider reads these values from real keys and refusals — is in the instrumented suite.
 */
class KeystorePlacementTest {
    private val tee = KeystorePlacement.SecureHardware(KeyCustody.NonExportable.Hardware(false))
    private val strongBox = KeystorePlacement.SecureHardware(KeyCustody.NonExportable.Hardware(true))

    @Test
    fun teeStrongBoxAndUnknownSecureAreHardware() {
        assertEquals(strongBox, placementOfSecurityLevel(Level.SECURITY_LEVEL_STRONGBOX))
        assertEquals(tee, placementOfSecurityLevel(Level.SECURITY_LEVEL_TRUSTED_ENVIRONMENT))
        assertEquals(tee, placementOfSecurityLevel(Level.SECURITY_LEVEL_UNKNOWN_SECURE), "secure, of an unnamed kind")
        listOf(Level.SECURITY_LEVEL_SOFTWARE, Level.SECURITY_LEVEL_UNKNOWN).forEach { level ->
            assertEquals(KeystorePlacement.NotSecureHardware, placementOfSecurityLevel(level), "level $level")
        }
    }

    @Test
    fun aLegacyKeyCountsAtTheTeeFloor() {
        assertEquals(tee, placementOfLegacyKeyInfo(insideSecureHardware = true))
        assertEquals(KeystorePlacement.NotSecureHardware, placementOfLegacyKeyInfo(insideSecureHardware = false))
    }

    @Test
    fun onlyAJustGeneratedKeyOutsideHardwareIsDeleted() {
        val software = KeystorePlacement.NotSecureHardware
        assertEquals(CustodyVerdict.DeleteAndRefuse, custodyVerdict(software, KeyOrigin.JustGenerated))
        assertEquals(CustodyVerdict.Refuse, custodyVerdict(KeystorePlacement.NotSecureHardware, KeyOrigin.Reattached))
        assertEquals(CustodyVerdict.Accept(tee.custody), custodyVerdict(tee, KeyOrigin.Reattached))
    }

    @Test
    fun aBusyStrongBoxIsAskedAgainThenTheTeeHoldsTheKey() {
        val busy = listOf(KeystoreFailureFact.Wrapper, coded(Ks.ERROR_KEYMINT_FAILURE, transient = true))
        assertEquals(StrongBoxRefusal.RetryStrongBox, strongBoxRefusal(busy, attempt = 1))
        assertEquals(
            toTee(Reason.SecureElementBusy, Ks.ERROR_KEYMINT_FAILURE),
            strongBoxRefusal(busy, attempt = STRONGBOX_ATTEMPTS),
            "a StrongBox that stays busy does not loop for ever",
        )
    }

    @Test
    fun everyOtherRefusalGoesToTheTeeWithItsReasonAndCode() {
        assertEquals(
            toTee(Reason.SecureElementAbsent, Ks.ERROR_UNIMPLEMENTED),
            strongBoxRefusal(listOf(KeystoreFailureFact.StrongBoxUnavailable, coded(Ks.ERROR_UNIMPLEMENTED)), 1),
        )
        mapOf(
            Ks.ERROR_UNIMPLEMENTED to Reason.UnsupportedParameters,
            Ks.ERROR_INCORRECT_USAGE to Reason.UnsupportedParameters,
            Ks.ERROR_KEYMINT_FAILURE to Reason.KeyMintFailure,
            Ks.ERROR_INTERNAL_SYSTEM_ERROR to Reason.SystemError,
            Ks.ERROR_KEY_CORRUPTED to Reason.Unclassified,
        ).forEach { (code, reason) ->
            val refusal = strongBoxRefusal(listOf(KeystoreFailureFact.Wrapper, coded(code)), 1)
            assertEquals(toTee(reason, code), refusal, "code $code")
        }
        assertEquals(
            StrongBoxRefusal.FallBackToTee(
                SecureElementFallback.ToTee(Reason.UnsupportedParameters, KeystoreCode.Unpublished),
            ),
            strongBoxRefusal(listOf(KeystoreFailureFact.SpecRejected), 1),
        )
        assertEquals(
            StrongBoxRefusal.FallBackToTee(SecureElementFallback.ToTee(Reason.Unclassified, KeystoreCode.Unpublished)),
            strongBoxRefusal(listOf(KeystoreFailureFact.Wrapper, KeystoreFailureFact.Unclassifiable), 1),
            "before API 33 the keystore names no reason, and the fallback says so",
        )
    }

    private fun coded(
        code: Int,
        transient: Boolean = false,
    ) = KeystoreFailureFact.Coded(numericErrorCode = code, transientFailure = transient)

    private fun toTee(
        reason: Reason,
        code: Int,
    ) = StrongBoxRefusal.FallBackToTee(SecureElementFallback.ToTee(reason, KeystoreCode.Public(code)))
}
