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
