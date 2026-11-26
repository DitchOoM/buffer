package com.ditchoom.buffer.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
    private val software = KeystorePlacement.NotSecureHardware(OfferedCustody.Tier(CustodyTier.NonExportableSoftware))

    @Test
    fun teeStrongBoxAndUnknownSecureAreHardware() {
        assertEquals(strongBox, placementOfSecurityLevel(Level.SECURITY_LEVEL_STRONGBOX))
        assertEquals(tee, placementOfSecurityLevel(Level.SECURITY_LEVEL_TRUSTED_ENVIRONMENT))
        assertEquals(tee, placementOfSecurityLevel(Level.SECURITY_LEVEL_UNKNOWN_SECURE), "secure, of an unnamed kind")
        assertEquals(software, placementOfSecurityLevel(Level.SECURITY_LEVEL_SOFTWARE))
    }

    @Test
    fun aLevelTheKeystoreWillNotNameIsRefusedAsUnreportedNotAsSoftware() {
        val unknown = placementOfSecurityLevel(Level.SECURITY_LEVEL_UNKNOWN)
        assertEquals(KeystorePlacement.NotSecureHardware(OfferedCustody.Unreported), unknown)
        val refusal = custodyVerdict(unknown, KeyOrigin.JustGenerated(SecureElementFallback.None))
        val failure = notHardware(ProtectedKeyAlgorithm.EcdsaP256, (refusal as CustodyVerdict.DeleteAndRefuse).offered)
        assertEquals(OfferedCustody.Unreported, failure.offered)
        assertEquals(CustodyTier.ExportableSoftware, failure.available, "an unnamed level vouches for no tier")
        val softwareRefusal = custodyVerdict(software, KeyOrigin.JustGenerated(SecureElementFallback.None))
        assertEquals(
            OfferedCustody.Tier(CustodyTier.NonExportableSoftware),
            (softwareRefusal as CustodyVerdict.DeleteAndRefuse).offered,
        )
    }

    @Test
    fun aLegacyKeyCountsAtTheTeeFloor() {
        assertEquals(tee, placementOfLegacyKeyInfo(insideSecureHardware = true))
        assertEquals(software, placementOfLegacyKeyInfo(insideSecureHardware = false))
    }

    @Test
    fun aReattachedKeyOutsideHardwareIsHandedOutNotRefused() {
        val honest =
            CustodyVerdict.Accept(KeyPlacement(KeyCustody.NonExportable.Software, SecureElementFallback.NotRecorded))
        // A key an earlier release stored outside hardware stays usable, reporting the custody it really has.
        assertEquals(honest, custodyVerdict(software, KeyOrigin.Reattached))
        val unnamed = KeystorePlacement.NotSecureHardware(OfferedCustody.Unreported)
        assertEquals(honest, custodyVerdict(unnamed, KeyOrigin.Reattached))
    }

    @Test
    fun onlyAJustGeneratedKeyOutsideHardwareIsDeleted() {
        val generated = KeyOrigin.JustGenerated(SecureElementFallback.None)
        assertTrue(custodyVerdict(software, generated) is CustodyVerdict.DeleteAndRefuse)
        assertTrue(custodyVerdict(software, KeyOrigin.Reattached) is CustodyVerdict.Accept)
    }

    @Test
    fun aReattachedKeySaysItsFallbackWasNotRecordedAndAGeneratedOneKeepsItsOwn() {
        assertEquals(
            CustodyVerdict.Accept(KeyPlacement(tee.custody, SecureElementFallback.NotRecorded)),
            custodyVerdict(tee, KeyOrigin.Reattached),
        )
        val toTee = weaker(Reason.ElementAbsent, AndroidKeystoreCode.Unpublished)
        assertEquals(
            CustodyVerdict.Accept(KeyPlacement(tee.custody, toTee)),
            custodyVerdict(tee, KeyOrigin.JustGenerated(toTee)),
        )
        assertEquals(
            CustodyVerdict.Accept(KeyPlacement(strongBox.custody, SecureElementFallback.None)),
            custodyVerdict(strongBox, KeyOrigin.JustGenerated(SecureElementFallback.None)),
        )
    }

    @Test
    fun aKeystoreThatFailedToAnswerLeavesPresenceUnknownNotFalse() {
        assertEquals(false, entryPresent(EntryLookup.Absent))
        listOf(EntryLookup.Secret, EntryLookup.OtherType, EntryLookup.Unloadable(keystoreFailed = false)).forEach {
            assertEquals(true, entryPresent(it), "$it is there")
        }
        val failure =
            kotlin.test.assertFailsWith<KeyStoreException.StorageFailure> {
                entryPresent(EntryLookup.Unloadable(keystoreFailed = true))
            }
        assertTrue(failure.retryable)
    }

    /**
     * StrongBox was asked for and did not refuse, yet the key is in the TEE: that is a fallback, not none. Where the level
     * cannot be read (before API 31, or a secure level of an unnamed kind) whether it fell back is not recorded.
     */
    @Test
    fun aStrongBoxRequestServedInTheTeeWithoutARefusalIsAFallback() {
        val accepted = KeyOrigin.JustGenerated(SecureElementFallback.None)
        assertEquals(
            CustodyVerdict.Accept(
                KeyPlacement(tee.custody, weaker(Reason.Unclassified, AndroidKeystoreCode.Unpublished)),
            ),
            custodyVerdict(tee, accepted),
        )
        assertEquals(
            CustodyVerdict.Accept(KeyPlacement(tee.custody, SecureElementFallback.NotRecorded)),
            custodyVerdict(placementOfLegacyKeyInfo(insideSecureHardware = true), accepted),
            "before API 31 nothing says whether StrongBox holds it",
        )
        assertEquals(
            CustodyVerdict.Accept(KeyPlacement(tee.custody, SecureElementFallback.NotRecorded)),
            custodyVerdict(placementOfSecurityLevel(Level.SECURITY_LEVEL_UNKNOWN_SECURE), accepted),
            "secure hardware of an unnamed kind may or may not be StrongBox",
        )
    }

    /** A failure the next call would only repeat (no such algorithm, an unreadable key spec) is not retryable. */
    @Test
    fun aDeterministicStoreFailureIsNotRetryable() {
        listOf<java.security.GeneralSecurityException>(
            java.security.NoSuchAlgorithmException(),
            java.security.NoSuchProviderException(),
            java.security.spec.InvalidKeySpecException(),
        ).forEach { failure ->
            val mapped = kotlin.test.assertFailsWith<KeyStoreException.StorageFailure> { mappingStoreFailures { throw failure } }
            assertTrue(!mapped.retryable, "${failure::class.simpleName} is not retryable")
        }
        val keystore =
            kotlin.test.assertFailsWith<KeyStoreException.StorageFailure> {
                mappingStoreFailures { throw java.security.KeyStoreException() }
            }
        assertTrue(keystore.retryable, "a keystore that could not answer is worth asking again")
    }

    @Test
    fun aBusyStrongBoxIsAskedAgainThenTheTeeHoldsTheKey() {
        val busy = listOf(KeystoreFailureFact.Wrapper, coded(Ks.ERROR_KEYMINT_FAILURE, transient = true))
        assertEquals(StrongBoxRefusal.RetryStrongBox, strongBoxRefusal(busy, attempt = 1))
        assertEquals(
            toTee(Reason.ElementBusy, Ks.ERROR_KEYMINT_FAILURE),
            strongBoxRefusal(busy, attempt = STRONGBOX_ATTEMPTS),
            "a StrongBox that stays busy does not loop for ever",
        )
    }

    @Test
    fun everyOtherRefusalGoesToTheTeeWithItsReasonAndCode() {
        assertEquals(
            toTee(Reason.ElementAbsent, Ks.ERROR_UNIMPLEMENTED),
            strongBoxRefusal(listOf(KeystoreFailureFact.StrongBoxUnavailable, coded(Ks.ERROR_UNIMPLEMENTED)), 1),
        )
        mapOf(
            Ks.ERROR_UNIMPLEMENTED to Reason.UnsupportedParameters,
            Ks.ERROR_INCORRECT_USAGE to Reason.UnsupportedParameters,
            Ks.ERROR_KEYMINT_FAILURE to Reason.ElementFailed,
            Ks.ERROR_INTERNAL_SYSTEM_ERROR to Reason.ElementFailed,
            Ks.ERROR_KEY_CORRUPTED to Reason.Unclassified,
        ).forEach { (code, reason) ->
            val refusal = strongBoxRefusal(listOf(KeystoreFailureFact.Wrapper, coded(code)), 1)
            assertEquals(toTee(reason, code), refusal, "code $code")
        }
        assertEquals(
            StrongBoxRefusal.FallBackToTee(weaker(Reason.UnsupportedParameters, AndroidKeystoreCode.Unpublished)),
            strongBoxRefusal(listOf(KeystoreFailureFact.SpecRejected), 1),
        )
        assertEquals(
            StrongBoxRefusal.FallBackToTee(weaker(Reason.Unclassified, AndroidKeystoreCode.Unpublished)),
            strongBoxRefusal(listOf(KeystoreFailureFact.Wrapper, KeystoreFailureFact.Unclassifiable), 1),
            "before API 33 the keystore names no reason, and the fallback says so",
        )
    }

    @Test
    fun aLookupThatFailedNeverLetsAKeyBeGeneratedOverIt() {
        assertEquals(EntryVerdict.Empty, entryVerdict(EntryLookup.Absent))
        assertEquals(EntryVerdict.Holds(ProtectedKeyAlgorithm.AesGcm), entryVerdict(EntryLookup.Secret))
        listOf(
            EntryLookup.OtherType,
            EntryLookup.Unloadable(keystoreFailed = true),
            EntryLookup.Unloadable(keystoreFailed = false),
        ).forEach { lookup -> assertTrue(entryVerdict(lookup) is EntryVerdict.Refuse, "$lookup must refuse") }
        val busy = entryVerdict(EntryLookup.Unloadable(keystoreFailed = true)) as EntryVerdict.Refuse
        assertTrue((busy.failure as KeyStoreException.StorageFailure).retryable)
    }

    private fun coded(
        code: Int,
        transient: Boolean = false,
    ) = KeystoreFailureFact.Coded(numericErrorCode = code, transientFailure = transient)

    private fun toTee(
        reason: Reason,
        code: Int,
    ) = StrongBoxRefusal.FallBackToTee(weaker(reason, AndroidKeystoreCode.Public(code)))

    private fun weaker(
        reason: Reason,
        code: AndroidKeystoreCode,
    ) = SecureElementFallback.ToWeakerSecureTier(reason, AndroidKeystoreFallbackDetail(code))
}
