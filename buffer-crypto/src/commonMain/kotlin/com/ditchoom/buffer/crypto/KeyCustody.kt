package com.ditchoom.buffer.crypto

/**
 * The complete, canonical description of a key's custody — where its secret lives and whether this
 * process can ever read it. Only *legal* states are constructible: exportability and backing are not
 * independent axes (a secure-element key that is exportable, or an in-memory key that is a dedicated
 * secure element, are physically impossible), so they are encoded as one sealed value rather than as
 * separate boolean fields that could disagree. [exportable], [KeyProvenance] ([provenance]), and
 * [dedicatedSecureElement] are all *derived* off this single value and therefore can never contradict
 * it.
 *
 * The three real states:
 *
 * | Key                                   | value                        | witness path |
 * |---------------------------------------|------------------------------|--------------|
 * | in-memory software key                | [ExportableSoftware]         | sync + async |
 * | non-exportable software (WebCrypto)   | [NonExportable.Software]     | async only   |
 * | secure element (Android / Apple)      | [NonExportable.Hardware]     | async only   |
 */
sealed interface KeyCustody {
    /** Ordered custody strength (weakest → strongest). Derived from the subtype, never stored. */
    val tier: CustodyTier

    /** In-process key: the material is a buffer this process can read. The software fallback. */
    data object ExportableSoftware : KeyCustody {
        override val tier: CustodyTier get() = CustodyTier.ExportableSoftware
    }

    /**
     * A key whose secret never enters process memory. `exportable == false` is guaranteed by *being*
     * this subtype, not by a boolean field, so a non-exportable-yet-readable key cannot be built.
     */
    sealed interface NonExportable : KeyCustody {
        /** Software isolation — the secret never reaches process memory (WebCrypto `extractable:false`). */
        data object Software : NonExportable {
            override val tier: CustodyTier get() = CustodyTier.NonExportableSoftware
        }

        /**
         * A secure element / OS keystore holds the secret. [dedicatedSecureElement] distinguishes a
         * dedicated element (StrongBox / Secure Enclave) from a TEE-only keystore.
         */
        data class Hardware(
            val dedicatedSecureElement: Boolean,
        ) : NonExportable {
            override val tier: CustodyTier get() = CustodyTier.Hardware
        }
    }
}

/** Ordered custody thresholds, weakest → strongest, for [KeyCustody.tier] comparisons. */
enum class CustodyTier {
    ExportableSoftware,
    NonExportableSoftware,
    Hardware,
}

/** `true` only for [KeyCustody.ExportableSoftware] — the one state whose secret this process can read. */
val KeyCustody.exportable: Boolean get() = this is KeyCustody.ExportableSoftware

/**
 * The 6.0 [KeyProvenance] projected off custody (kept for source compatibility). Only a
 * [KeyCustody.NonExportable.Hardware] key is [KeyProvenance.Hardware]; both software states — whether
 * exportable or not — are [KeyProvenance.Software], because provenance is *where the key lives*, not
 * whether it is exportable.
 */
val KeyCustody.provenance: KeyProvenance get() =
    if (this is KeyCustody.NonExportable.Hardware) KeyProvenance.Hardware else KeyProvenance.Software

/** `true` when the key lives in a dedicated secure element (StrongBox / Secure Enclave). */
val KeyCustody.dedicatedSecureElement: Boolean get() =
    this is KeyCustody.NonExportable.Hardware && dedicatedSecureElement

/**
 * Whether a hardware key that asked for a dedicated secure element (Android StrongBox) was made in the TEE instead,
 * and why. The key's [KeyCustody] says where it lives either way; this says why it is not where it asked to be, with
 * the keystore's code, so a caller can log it.
 */
sealed interface SecureElementFallback {
    /** No fallback happened: the key is where it asked to be, or it never asked for a dedicated secure element. */
    data object None : SecureElementFallback

    /**
     * Not known: the key was persisted earlier and reattached, and the keystore keeps no record of whether its
     * generation fell back. Its [KeyCustody] still says whether StrongBox holds it.
     */
    data object NotRecorded : SecureElementFallback

    /** Asked for StrongBox, made in the TEE because of [reason]; [keystoreCode] is what the keystore said. */
    @ConsistentCopyVisibility
    data class ToTee internal constructor(
        val reason: SecureElementFallbackReason,
        val keystoreCode: KeystoreCode,
    ) : SecureElementFallback
}

/** Why a StrongBox generation was retried in the TEE. */
enum class SecureElementFallbackReason {
    /** The device has no StrongBox for this key. */
    SecureElementAbsent,

    /** StrongBox does not implement the key's algorithm, size or purpose. */
    UnsupportedParameters,

    /** StrongBox stayed busy or unreachable through every attempt. */
    SecureElementBusy,

    /** The StrongBox KeyMint failed the request. */
    KeyMintFailure,

    /** The keystore reported a generic system error for the StrongBox request. */
    SystemError,

    /** The keystore gave no code that says why (before Android 13 it publishes none), or a code not named above. */
    Unclassified,
}

/** An Android keystore error code, as the platform publishes it. */
sealed interface KeystoreCode {
    /** `android.security.KeyStoreException.getNumericErrorCode()`, from API 33. */
    @ConsistentCopyVisibility
    data class Public internal constructor(
        val code: Int,
    ) : KeystoreCode

    /** The keystore published no code (before API 33, or no keystore exception in the cause chain). */
    data object Unpublished : KeystoreCode
}

/** What a platform reported about a key's or an algorithm's custody when it fell short. */
sealed interface OfferedCustody {
    /** The platform named this tier. */
    @ConsistentCopyVisibility
    data class Tier internal constructor(
        val tier: CustodyTier,
    ) : OfferedCustody

    /** The platform named no level it would vouch for (Android `SECURITY_LEVEL_UNKNOWN`). */
    data object Unreported : OfferedCustody
}

/** A key type that records its [SecureElementFallback]. */
internal interface FallbackNoted {
    val secureElementFallback: SecureElementFallback
}

/** This signing key's [SecureElementFallback]: [SecureElementFallback.None] for any key that did not fall back. */
val SigningKey.secureElementFallback: SecureElementFallback get() = fallbackOf(this)

/** This AES-GCM key's [SecureElementFallback]. */
val AesGcmKey.secureElementFallback: SecureElementFallback get() = fallbackOf(this)

/** This key-agreement private key's [SecureElementFallback]. */
val KeyAgreementPrivateKey.secureElementFallback: SecureElementFallback get() = fallbackOf(this)

private fun fallbackOf(key: Any): SecureElementFallback =
    when (key) {
        is FallbackNoted -> key.secureElementFallback
        else -> SecureElementFallback.None
    }
