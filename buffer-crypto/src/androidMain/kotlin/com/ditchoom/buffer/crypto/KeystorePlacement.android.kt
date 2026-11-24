package com.ditchoom.buffer.crypto

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.InvalidAlgorithmParameterException
import java.security.Key
import java.security.KeyFactory
import java.security.Security
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import android.security.KeyStoreException as AndroidKeyStoreException

/*
 * The two decisions the Android Keystore provider makes from what the keystore reports: where a key
 * it just generated or reattached actually lives, and whether a refused StrongBox generation may be
 * retried in the TEE. Both are pure functions of plain values so they are tested off-device; the
 * provider only reads the values (KeyInfo, the exception chain) and acts on the answer.
 */

/** Where the keystore says one key lives, from its `KeyInfo`. */
internal sealed interface KeystorePlacement {
    /** A TEE or StrongBox key: [custody] is what the key reports, never what was requested. */
    data class SecureHardware(
        val custody: KeyCustody.NonExportable.Hardware,
    ) : KeystorePlacement

    /**
     * Software, or a level the keystore will not name (`SECURITY_LEVEL_UNKNOWN*`, or a pre-31
     * `isInsideSecureHardware() == false`). Such a key is never handed out as hardware.
     */
    data object NotSecureHardware : KeystorePlacement
}

/** API 31+: the placement a `KeyInfo.getSecurityLevel()` value names. */
internal fun placementOfSecurityLevel(securityLevel: Int): KeystorePlacement =
    when (securityLevel) {
        KeyProperties.SECURITY_LEVEL_STRONGBOX ->
            KeystorePlacement.SecureHardware(KeyCustody.NonExportable.Hardware(dedicatedSecureElement = true))
        KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ->
            KeystorePlacement.SecureHardware(KeyCustody.NonExportable.Hardware(dedicatedSecureElement = false))
        else -> KeystorePlacement.NotSecureHardware
    }

/**
 * Pre-31, where `KeyInfo` exposes only `isInsideSecureHardware()`: a secure key is StrongBox only if
 * it came from a StrongBox generation that the keystore accepted ([placedInStrongBox]).
 */
internal fun placementOfLegacyKeyInfo(
    insideSecureHardware: Boolean,
    placedInStrongBox: Boolean,
): KeystorePlacement =
    if (insideSecureHardware) {
        KeystorePlacement.SecureHardware(KeyCustody.NonExportable.Hardware(dedicatedSecureElement = placedInStrongBox))
    } else {
        KeystorePlacement.NotSecureHardware
    }

/** One link of a keystore failure's cause chain, reduced to what the StrongBox fallback decides on. */
internal sealed interface KeystoreFailureFact {
    /** `StrongBoxUnavailableException`: this device has no StrongBox for this key. */
    data object StrongBoxUnavailable : KeystoreFailureFact

    /** API 33+ `android.security.KeyStoreException`: its public error code and transient flag. */
    data class Coded(
        val numericErrorCode: Int,
        val transientFailure: Boolean,
    ) : KeystoreFailureFact

    /** A keystore exception before API 33, which publishes no code to read. */
    data object Unclassifiable : KeystoreFailureFact

    /** `InvalidAlgorithmParameterException`: the spec itself was refused. */
    data object SpecRejected : KeystoreFailureFact

    /** Any other link (a wrapping `ProviderException`, a `GeneralSecurityException`). */
    data object Wrapper : KeystoreFailureFact
}

/** What a refused StrongBox generation allows. */
internal sealed interface StrongBoxRefusal {
    /** StrongBox cannot hold this key (absent, or it does not implement the parameters): use the TEE. */
    data object FallBackToTee : StrongBoxRefusal

    /** StrongBox is busy or momentarily unreachable: retrying StrongBox may succeed, so do not settle for the TEE. */
    data object Transient : StrongBoxRefusal

    /** A refusal that is neither: surface it as the keystore failure it is. */
    data object Terminal : StrongBoxRefusal
}

/**
 * The public codes under which a StrongBox refuses a spec it cannot hold: `ERROR_UNIMPLEMENTED`
 * carries the KeyMint `HARDWARE_TYPE_UNAVAILABLE` / `UNSUPPORTED_*` refusals, `ERROR_INCORRECT_USAGE`
 * an unsupported purpose, and `ERROR_INTERNAL_SYSTEM_ERROR` is how some StrongBox implementations
 * refuse a key size the specification requires of them.
 */
private val strongBoxCannotHoldCodes =
    setOf(
        AndroidKeyStoreException.ERROR_UNIMPLEMENTED,
        AndroidKeyStoreException.ERROR_INCORRECT_USAGE,
        AndroidKeyStoreException.ERROR_INTERNAL_SYSTEM_ERROR,
    )

/**
 * Decides on a refused StrongBox generation from its cause chain. A StrongBox-unavailable or
 * unsupported-parameter refusal falls back to the TEE, and the key then reports its own placement; a
 * transient refusal never does, because a key made in the TEE while StrongBox was merely busy would
 * be weaker than the device offers for the rest of its life.
 */
internal fun strongBoxRefusal(chain: List<KeystoreFailureFact>): StrongBoxRefusal {
    if (KeystoreFailureFact.StrongBoxUnavailable in chain) return StrongBoxRefusal.FallBackToTee
    val coded = chain.filterIsInstance<KeystoreFailureFact.Coded>()
    return when {
        coded.any { it.transientFailure } -> StrongBoxRefusal.Transient
        coded.any { it.numericErrorCode in strongBoxCannotHoldCodes } -> StrongBoxRefusal.FallBackToTee
        coded.isNotEmpty() -> StrongBoxRefusal.Terminal
        KeystoreFailureFact.SpecRejected in chain -> StrongBoxRefusal.FallBackToTee
        // Before API 33 a keystore refusal carries no public code, so a busy StrongBox cannot be told
        // from one that cannot hold the key; the TEE retry is the only safe choice left.
        KeystoreFailureFact.Unclassifiable in chain -> StrongBoxRefusal.FallBackToTee
        else -> StrongBoxRefusal.Terminal
    }
}

/** The cause chain of [failure] as [KeystoreFailureFact]s, outermost first. */
internal fun keystoreFailureFacts(failure: Throwable): List<KeystoreFailureFact> {
    val facts = mutableListOf<KeystoreFailureFact>()
    var current: Throwable? = failure
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        facts +=
            when (current) {
                is StrongBoxUnavailableException -> KeystoreFailureFact.StrongBoxUnavailable
                is AndroidKeyStoreException ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        KeystoreFailureFact.Coded(current.numericErrorCode, current.isTransientFailure)
                    } else {
                        KeystoreFailureFact.Unclassifiable
                    }
                is InvalidAlgorithmParameterException -> KeystoreFailureFact.SpecRejected
                else -> KeystoreFailureFact.Wrapper
            }
        current = current.cause
    }
    return facts
}

/** The `KeyInfo` of an AndroidKeyStore [key] — a private key or a secret key. */
internal fun keyInfoOf(key: Key): KeyInfo =
    when (key) {
        is SecretKey ->
            SecretKeyFactory
                .getInstance(key.algorithm, ANDROID_KEY_STORE)
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        else -> KeyFactory.getInstance(key.algorithm, ANDROID_KEY_STORE).getKeySpec(key, KeyInfo::class.java)
    }

/** Whether this runtime registers the `AndroidKeyStore` JCA provider at all (every device; no host JVM). */
internal fun androidKeystoreProviderRegistered(): Boolean = Security.getProvider(ANDROID_KEY_STORE) != null

/** The Android Keystore backend refused for [finding]. */
internal fun keystoreRefused(finding: CapabilityFinding.Keystore): ProtectedKeyResolution =
    ProtectedKeyResolution.Refused(ProtectedKeyBackend.AndroidKeystore, finding)
