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
 * Whether a hardware key that asked for a dedicated secure element (Android StrongBox) was made in a weaker secure tier
 * instead (the Android TEE), and why. The key's [KeyCustody] says where it lives either way; this says why it is not
 * where it asked to be, so a caller can log it.
 */
sealed interface SecureElementFallback {
    /** No fallback happened: the key is where it asked to be, or it never asked for a dedicated secure element. */
    data object None : SecureElementFallback

    /**
     * Not known: the key was persisted earlier and reattached, and the platform keeps no record of whether its
     * generation fell back. Its [KeyCustody] still says whether a dedicated secure element holds it.
     */
    data object NotRecorded : SecureElementFallback

    /**
     * Asked for a dedicated secure element and made in the platform's other secure hardware because of [reason].
     * [detail] is what the platform itself reported, for logs; on Android it is an `AndroidKeystoreFallbackDetail`.
     */
    @ConsistentCopyVisibility
    data class ToWeakerSecureTier internal constructor(
        val reason: SecureElementFallbackReason,
        val detail: SecureElementFallbackDetail,
    ) : SecureElementFallback
}

/**
 * Why a dedicated secure element did not take a key, in terms every platform shares. A platform's own finer reason is
 * carried in [SecureElementFallback.ToWeakerSecureTier.detail], so a new platform maps onto these arms, with
 * [Unclassified] for anything it cannot place, instead of adding one.
 */
sealed interface SecureElementFallbackReason {
    /** The device has no dedicated secure element for this key. */
    data object ElementAbsent : SecureElementFallbackReason

    /** The dedicated secure element does not implement the key's algorithm, size or purpose. */
    data object UnsupportedParameters : SecureElementFallbackReason

    /** The dedicated secure element stayed busy or unreachable through every attempt. */
    data object ElementBusy : SecureElementFallbackReason

    /** The dedicated secure element, or the platform service in front of it, failed the request. */
    data object ElementFailed : SecureElementFallbackReason

    /** The platform gave nothing that says why. */
    data object Unclassified : SecureElementFallbackReason
}

/**
 * What the platform itself reported when a dedicated secure element refused a key. Open-ended on purpose: each
 * platform adds its own subtype in its own source set, and a `when` over it needs an `else`, so a new platform's detail
 * never breaks existing code. Only this library constructs one.
 */
abstract class SecureElementFallbackDetail internal constructor()

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
