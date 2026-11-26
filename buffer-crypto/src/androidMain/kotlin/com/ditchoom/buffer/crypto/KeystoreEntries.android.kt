package com.ditchoom.buffer.crypto

/*
 * What the Android Keystore provider decides from one alias lookup: whether a get-or-generate may generate, whether
 * an entry is there for contains/delete, and the store failure each refusal stands for. Pure functions of the lookup,
 * so they are tested off-device.
 */

/** What `KeyStore.getKey` said about an alias. */
internal sealed interface EntryLookup {
    /** `null`: the keystore reports `KEY_NOT_FOUND`. */
    data object Absent : EntryLookup

    data class Private(
        val algorithm: ProtectedKeyAlgorithm,
    ) : EntryLookup

    data object Secret : EntryLookup

    /** A key of a type this store never writes. */
    data object OtherType : EntryLookup

    /**
     * `UnrecoverableKeyException`. With a keystore exception as its cause the keystore failed to answer; without one it
     * could not load the key (android-36 drops the cause when rethrowing `KeyPermanentlyInvalidatedException`).
     */
    data class Unloadable(
        val keystoreFailed: Boolean,
    ) : EntryLookup
}

internal sealed interface EntryVerdict {
    /** No key: a get-or-generate may make one. */
    data object Empty : EntryVerdict

    data class Holds(
        val algorithm: ProtectedKeyAlgorithm,
    ) : EntryVerdict

    /** Something is there or may be: never generate over it. */
    data class Refuse(
        val failure: KeyStoreException,
    ) : EntryVerdict
}

/**
 * Whether [lookup] found an entry. An entry that cannot be loaded is still there; a keystore that failed to answer
 * leaves presence unknown, which is a typed failure rather than a `false` that would let [KeyStore.delete] report no
 * key while one is stored.
 */
internal fun entryPresent(lookup: EntryLookup): Boolean =
    when (lookup) {
        EntryLookup.Absent -> false
        is EntryLookup.Private, EntryLookup.Secret, EntryLookup.OtherType -> true
        is EntryLookup.Unloadable ->
            if (lookup.keystoreFailed) throw KeyStoreException.StorageFailure(retryable = true) else true
    }

/**
 * A key the lookup found that the read of its entry then could not return: the keystore answered one and not the
 * other.
 */
internal fun reattachFailed(): KeyStoreException = KeyStoreException.StorageFailure(retryable = true)

/** The store failure a keystore that refused to delete an entry stands for. */
internal fun deleteRefused(): KeyStoreException = KeyStoreException.StorageFailure(retryable = true)

internal fun entryVerdict(lookup: EntryLookup): EntryVerdict =
    when (lookup) {
        EntryLookup.Absent -> EntryVerdict.Empty
        is EntryLookup.Private -> EntryVerdict.Holds(lookup.algorithm)
        EntryLookup.Secret -> EntryVerdict.Holds(ProtectedKeyAlgorithm.AesGcm)
        EntryLookup.OtherType -> EntryVerdict.Refuse(KeyStoreException.CorruptEntry())
        is EntryLookup.Unloadable ->
            if (lookup.keystoreFailed) {
                EntryVerdict.Refuse(KeyStoreException.StorageFailure(retryable = true))
            } else {
                EntryVerdict.Refuse(KeyStoreException.CorruptEntry())
            }
    }
