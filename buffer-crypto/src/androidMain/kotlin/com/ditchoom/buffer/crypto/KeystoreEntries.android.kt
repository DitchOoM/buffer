package com.ditchoom.buffer.crypto

import android.security.keystore.KeyProperties
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.security.PrivateKey
import java.security.ProviderException
import java.security.interfaces.ECKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.InvalidKeySpecException

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
     * `UnrecoverableKeyException`. With a keystore exception as its cause the keystore failed to answer.
     * Without one it could not build the key from the entry (no or an unparsable certificate, an
     * unsupported key type), or the key was permanently invalidated: `AndroidKeyStoreSpi.engineGetKey`
     * rethrows `KeyPermanentlyInvalidatedException` as a bare `UnrecoverableKeyException`, so the two
     * cannot be told apart. Only a key bound to user authentication is invalidated, which the persistent
     * store's keys never are, so for them it is a corrupt entry.
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

/** What a load of a key of one algorithm does with the lookup of its alias. */
internal sealed interface LoadVerdict {
    /** A key of the asked-for algorithm is there: read its entry. */
    data object Load : LoadVerdict

    /** No key, or a key of another kind: the load answers `null`. */
    data object NoSuchKey : LoadVerdict

    data class Refuse(
        val failure: KeyStoreException,
    ) : LoadVerdict
}

internal fun loadVerdict(
    lookup: EntryLookup,
    expected: ProtectedKeyAlgorithm,
): LoadVerdict =
    when (lookup) {
        EntryLookup.Absent, EntryLookup.OtherType -> LoadVerdict.NoSuchKey
        is EntryLookup.Private -> if (lookup.algorithm == expected) LoadVerdict.Load else LoadVerdict.NoSuchKey
        EntryLookup.Secret -> if (expected == ProtectedKeyAlgorithm.AesGcm) LoadVerdict.Load else LoadVerdict.NoSuchKey
        is EntryLookup.Unloadable ->
            when (val verdict = entryVerdict(lookup)) {
                is EntryVerdict.Refuse -> LoadVerdict.Refuse(verdict.failure)
                EntryVerdict.Empty, is EntryVerdict.Holds -> LoadVerdict.NoSuchKey
            }
    }

/**
 * What a private key under an alias is. Only a P-256 key is one this store writes: its keystore-recorded [purposes]
 * tell agreement from signing. Any other key, an RSA or a P-384 one, is [EntryLookup.OtherType]; reading it as P-256
 * would build a wrong public key.
 */
internal fun privateEntryLookup(
    params: ECParameterSpec?,
    purposes: () -> Int,
): EntryLookup =
    when {
        params == null || !isP256(params) -> EntryLookup.OtherType
        purposes() and KeyProperties.PURPOSE_AGREE_KEY != 0 -> EntryLookup.Private(ProtectedKeyAlgorithm.EcdhP256)
        else -> EntryLookup.Private(ProtectedKeyAlgorithm.EcdsaP256)
    }

/** The EC domain [privateKey] lives on, or `null` for a key that is not an EC key. */
internal fun ecParamsOf(privateKey: PrivateKey): ECParameterSpec? = (privateKey as? ECKey)?.params

private fun isP256(params: ECParameterSpec): Boolean = params.order == p256.order && params.curve == p256.curve

private val p256: ECParameterSpec by lazy {
    AlgorithmParameters
        .getInstance("EC")
        .apply { init(ECGenParameterSpec(SECP256R1)) }
        .getParameterSpec(ECParameterSpec::class.java)
}

/**
 * Runs a store [block] (a lookup, a read of a stored entry or its `KeyInfo`, a listing, a delete), normalizing a
 * keystore failure to a [KeyStoreException.StorageFailure]: a store that could not answer, not a key that failed. It
 * is retryable unless the failure is one the next call would repeat (see [deterministic]). A [CryptoException] thrown
 * inside passes through.
 */
internal inline fun <T> mappingStoreFailures(block: () -> T): T =
    try {
        block()
    } catch (e: CryptoException) {
        throw e
    } catch (e: GeneralSecurityException) {
        throw KeyStoreException.StorageFailure(retryable = !deterministic(e))
    } catch (_: ProviderException) {
        throw KeyStoreException.StorageFailure(retryable = true)
    }

/**
 * A failure the next call would only repeat: no such algorithm or provider, or a key spec the provider cannot read.
 * Anything else may be a keystore that could not answer this time.
 */
internal fun deterministic(failure: GeneralSecurityException): Boolean =
    failure is NoSuchAlgorithmException || failure is NoSuchProviderException || failure is InvalidKeySpecException

/** Why a key this provider generated and refused is still under its alias. */
internal sealed interface Undeleted {
    /** The keystore placed it outside secure hardware. */
    data class OutsideHardware(
        val alg: ProtectedKeyAlgorithm,
        val offered: OfferedCustody,
    ) : Undeleted

    /** Its placement could not be read. */
    data object Unconfirmed : Undeleted
}

/** The failure a lookup of an alias still holding a refused key throws: the generation's own refusal. */
internal fun Undeleted.failure(): CryptoException =
    when (this) {
        is Undeleted.OutsideHardware -> notHardware(alg, offered)
        Undeleted.Unconfirmed -> KeyStoreException.StorageFailure(retryable = true)
    }

/**
 * Keys the provider generated and refused. Each is deleted at once; one whose delete fails is remembered, so a later
 * lookup in this process deletes it before anything else and never reattaches it as a stored key: the refusal stands
 * until it is gone. Only the provider's keystore critical section touches it.
 */
internal class DiscardedKeys(
    private val keyStore: java.security.KeyStore,
) {
    private val undeleted = mutableMapOf<String, Undeleted>()

    /**
     * Deletes [alias] without asking `containsAlias` first, which answers `false` for a keystore error; deleting a
     * missing alias is a no-op. A delete that fails remembers [why].
     */
    fun discard(
        alias: String,
        why: Undeleted,
    ) {
        if (!deleted(alias)) undeleted[alias] = why
    }

    /** Before a key lookup of [alias]: deletes a refused key still under it, or throws its refusal while it cannot. */
    fun clearOrRefuse(alias: String) {
        val why = undeleted[alias] ?: return
        if (!deleted(alias)) throw why.failure()
        undeleted.remove(alias)
    }

    /** [alias] was deleted by the store's own delete. */
    fun forget(alias: String) {
        undeleted.remove(alias)
    }

    private fun deleted(alias: String): Boolean =
        try {
            keyStore.deleteEntry(alias)
            true
        } catch (_: java.security.KeyStoreException) {
            false
        }
}
