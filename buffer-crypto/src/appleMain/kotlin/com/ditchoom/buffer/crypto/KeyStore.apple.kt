@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_AUTH
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_BUFFER
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_CANCELED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_CORRUPT
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_DENIED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_DUPLICATE
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_INPUT
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_INTERACTION
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_LOCKED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_NO_KEYCHAIN
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_RETRY
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_OK
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_aliases
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_contains
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_delete
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_get
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_migrate
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_put
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_secure_enclave_available
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.posix.size_tVar
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Apple: a durable [AppleKeychainKeyStore] wherever the hardware has a Secure Enclave — each
 * persistent key's private scalar lives in the Enclave ([KeyCustody.NonExportable.Hardware]) and the
 * Keychain durably holds its restore record keyed by (`kSecAttrService` = [KeyStoreConfig.name],
 * `kSecAttrAccount` = alias). The Enclave is probed on first use; a refused probe answers that call
 * with a typed [KeyStoreException] ([KeyStoreException.AccessDenied] for a binary without the
 * entitlement, [KeyStoreException.DeviceLocked] for a probe the OS refused with `errSecInteractionNotAllowed`,
 * a retryable [KeyStoreException.StorageFailure] otherwise) and is probed again on the next, so a device with an
 * Enclave never ends up on a weaker or non-durable store.
 *
 * Every Apple host where CryptoKit reports no Enclave (`SecureEnclave.isAvailable` is false: the simulators, an Intel
 * Mac without a T2 chip, a virtual machine) uses an in-process, **non-durable** software store
 * ([KeyCustody.ExportableSoftware]) — honest about the weaker custody (a `requireTier` caller is refused rather than
 * silently upgraded), but keys there do not survive a process restart. A consumer needing durability without the
 * Enclave supplies [KeyStoreConfig.storage].
 */
internal actual fun platformKeyStore(config: KeyStoreConfig): KeyStore =
    when {
        config.storage != null -> SoftwareKeyStore(config.storage)
        bcks_secure_enclave_available() == 0 -> SoftwareKeyStore(InMemoryKeyStorage())
        else -> RetryingKeyStore { openAppleKeychainStore(config.name, appleEnclaveProbe()) }
    }

/** One attempt to open the Keychain-backed store named [name], given this call's Enclave [probe]. */
internal fun openAppleKeychainStore(
    name: String,
    probe: EnclaveProbe,
): PlatformStoreOpening =
    when (probe) {
        EnclaveProbe.Usable -> PlatformStoreOpening.Opened(AppleKeychainKeyStore(enclaveProvider, name))
        EnclaveProbe.NotEntitled -> PlatformStoreOpening.Refused(KeyStoreException.AccessDenied())
        EnclaveProbe.Locked -> PlatformStoreOpening.Refused(KeyStoreException.DeviceLocked())
        EnclaveProbe.Refused -> PlatformStoreOpening.Refused(KeyStoreException.StorageFailure(retryable = true))
        // Settled for the process: no later call finds an Enclave on this hardware.
        EnclaveProbe.NotPresent -> PlatformStoreOpening.Refused(KeyStoreException.StorageFailure(retryable = false))
    }

/**
 * Where every Apple store's keychain work runs: one thread at a time, so a call that blocks inside Security.framework
 * (a macOS keychain access prompt, a slow file keychain) parks one thread while the other callers suspend, instead of
 * each blocking a [Dispatchers.Default] worker on the shim's serial queue.
 */
internal val keychainDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

/**
 * The failure an Enclave generate status names. [BCKS_ERR_LOCKED] is an Enclave call the OS refused with
 * `errSecInteractionNotAllowed`, which is what a locked device is documented to return; [BCKS_ERR_AUTH] is a
 * LocalAuthentication refusal while binding a key to user authentication (no passcode or biometry to bind it to).
 */
internal fun enclaveGenerateFailure(status: Int): HardwareKeyException =
    when (status) {
        BCKS_ERR_LOCKED -> HardwareKeyException.DeviceLocked()
        // The binary lacks the entitlement the Enclave requires: a definite answer, not a passing failure.
        BCKS_ERR_DENIED -> HardwareKeyException.AccessDenied()
        else -> HardwareKeyException.SecureElementUnavailable()
    }

/**
 * The failure an Enclave sign status names. [BCKS_ERR_LOCKED] is a sign the OS refused with
 * `errSecInteractionNotAllowed` on a key bound to no user authentication: its protection class is unavailable, as for
 * an older key made under a when-unlocked class on a locked device.
 */
internal fun enclaveSignFailure(status: Int): HardwareKeyException =
    when (status) {
        BCKS_ERR_INPUT -> HardwareKeyException.KeyInvalidated()
        BCKS_ERR_LOCKED -> HardwareKeyException.DeviceLocked()
        else -> HardwareKeyException.TransientHardwareFailure(retryable = false)
    }

/**
 * The [KeyStoreException] a keychain status names. [BCKS_ERR_LOCKED] is `errSecInteractionNotAllowed` on iOS, watchOS
 * and tvOS, the status a protection class unavailable while the device is locked is documented to return, and is
 * [KeyStoreException.DeviceLocked] so a caller can wait for the unlock.
 */
internal fun keychainFailure(status: Int): KeyStoreException =
    when (status) {
        BCKS_ERR_LOCKED -> KeyStoreException.DeviceLocked()
        BCKS_ERR_INTERACTION -> KeyStoreException.InteractionRequired()
        BCKS_ERR_CANCELED, BCKS_ERR_AUTH -> KeyStoreException.AuthenticationDeclined()
        BCKS_ERR_DENIED -> KeyStoreException.AccessDenied()
        BCKS_ERR_CORRUPT -> KeyStoreException.CorruptEntry()
        // No keychain answered: early in boot it may not be up yet, so a later call can succeed.
        BCKS_ERR_RETRY, BCKS_ERR_DUPLICATE, BCKS_ERR_NO_KEYCHAIN -> KeyStoreException.StorageFailure(retryable = true)
        else -> KeyStoreException.StorageFailure(retryable = false)
    }

/** Whether a protection-class move is asked again on the next call. */
internal sealed interface MigrationStep {
    /** Done, or refused in a way the next call would only repeat: not asked again for this store. */
    data object Settled : MigrationStep

    /** Refused for now (a locked device, a busy keychain): asked again on the next call. */
    data object TryAgain : MigrationStep
}

/** What a [bcks_keychain_migrate] status means for the next call. */
internal fun migrationStep(status: Int): MigrationStep =
    when (status) {
        BCKS_ERR_LOCKED, BCKS_ERR_RETRY, BCKS_ERR_INTERACTION, BCKS_ERR_NO_KEYCHAIN -> MigrationStep.TryAgain
        else -> MigrationStep.Settled
    }

/**
 * A persistent, alias-addressable [KeyStore] whose keys are Secure Enclave P-256 signing and agreement keys
 * ([KeyCustody.NonExportable.Hardware]). The Enclave holds the private scalar; the Keychain durably
 * holds each key's restore record (public point + encrypted Enclave blob) as a generic-password item
 * keyed by (`service` = [name], `account` = alias), so independent stores (distinct
 * [KeyStoreConfig.name]) never collide. The record is opaque store-owned bytes — never a private key.
 *
 * The ephemeral [KeyProvider] methods delegate to [provider] (throwaway keys); the alias methods
 * persist — a key from [getOrGenerateSigning] / [loadSigning] survives `close()` and process restart
 * until [delete]d (a persistent key's `close()` does not touch the Keychain).
 *
 * Serves what the Enclave backs non-exportably: ECDSA P-256 signing and — where the generate-and-agree
 * probe holds — ECDH P-256 key agreement. A get-or-generate for anything else (other schemes,
 * AES-GCM, X25519) throws [HardwareKeyException.AlgorithmNotEligible] — consult [custodyFor] /
 * [eligible] first, or supply a software [KeyStoreConfig.storage] for a persistent key the Enclave
 * cannot hold.
 *
 * Both key kinds are (public point + restore blob) records, so the record's kind byte is what keeps
 * them apart: a kind-mismatched load answers `null` and a kind-mismatched get-or-generate raises
 * [KeyStoreException.AliasMismatch], never a mistyped handle.
 *
 * Records an earlier release wrote under `WhenUnlockedThisDeviceOnly` are moved to
 * `AfterFirstUnlockThisDeviceOnly` without an opt-in, on first use (`SECURITY.md` says where that class is enforced).
 * The move is one-way. A record is restore data, not a private key, and an older Enclave key keeps its own class.
 *
 * Every keychain call runs on [keychainDispatcher].
 */
internal class AppleKeychainKeyStore(
    private val provider: SecureEnclaveHardwareKeyProvider,
    private val records: KeychainRecords,
) : KeyStore {
    constructor(provider: SecureEnclaveHardwareKeyProvider, name: String) :
        this(provider, ShimKeychainRecords(name, DEFAULT_ALIASES_CAPACITY))

    /**
     * The Enclave's custody for an algorithm it holds; for one it cannot hold, [HardwareKeyException.AlgorithmNotEligible],
     * since no key of it will ever come from this store.
     */
    override fun custodyFor(alg: ProtectedKeyAlgorithm): KeyCustody =
        if (provider.eligible(alg)) provider.custody else throw HardwareKeyException.AlgorithmNotEligible()

    override fun eligible(alg: ProtectedKeyAlgorithm): Boolean = provider.eligible(alg)

    // --- KeyProvider (ephemeral) — delegate to the Enclave provider --------------------------------

    override suspend fun generateSigning(
        scheme: SignatureScheme,
        spec: ProtectedKeySpec,
    ): SigningKey = provider.generateSigning(scheme, spec)

    override suspend fun generateAesGcm(spec: ProtectedKeySpec): AesGcmKey = provider.generateAesGcm(spec)

    override suspend fun generateKeyAgreement(
        curve: KeyAgreementCurve,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair = provider.generateKeyAgreement(curve, spec)

    // --- Persistent alias lifecycle ----------------------------------------------------------------

    override suspend fun getOrGenerateSigning(
        alias: String,
        scheme: SignatureScheme,
        spec: ProtectedKeySpec,
    ): SigningKey {
        requireValidAlias(alias)
        if (!provider.eligible(scheme.toProtectedKeyAlgorithm())) throw HardwareKeyException.AlgorithmNotEligible()
        return getOrGenerate(
            alias,
            ProtectedKeyAlgorithm.EcdsaP256,
            attach = { record -> provider.signingFromRecord(record, spec) },
            generate = { provider.generatePersistentSigning(spec) },
            release = { it.close() },
        )
    }

    override suspend fun getOrGenerateAesGcm(
        alias: String,
        spec: ProtectedKeySpec,
    ): AesGcmKey {
        requireValidAlias(alias)
        // The Secure Enclave backs no app-controlled symmetric key.
        throw HardwareKeyException.AlgorithmNotEligible()
    }

    override suspend fun getOrGenerateKeyAgreement(
        alias: String,
        curve: KeyAgreementCurve,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair {
        requireValidAlias(alias)
        // Only ECDH P-256, and only where the probed Enclave agreement support holds; X25519 has no
        // Secure Enclave backing on any OS version.
        if (curve != KeyAgreementCurve.P256 || !provider.eligible(ProtectedKeyAlgorithm.EcdhP256)) {
            throw HardwareKeyException.AlgorithmNotEligible()
        }
        return getOrGenerate(
            alias,
            ProtectedKeyAlgorithm.EcdhP256,
            attach = { record -> provider.keyAgreementFromRecord(record, spec) },
            generate = { provider.generatePersistentKeyAgreement(spec) },
            release = { it.close() },
        )
    }

    /**
     * Re-attaches the key under [alias] when its record says [expected], or generates one and adds its record. The add
     * never replaces: when another writer added a record under [alias] first, that one is the key, and the one this
     * call generated is released — an Enclave key exists only through its record, so nothing of it is left behind. A
     * generated key whose record is not added is released too.
     *
     * An add refused as a duplicate while no record can be read is asked once more: a record deleted in between lets
     * the second add succeed, and a record still invisible to this process (another app's item, or one this process may
     * not read) is [KeyStoreException.AccessDenied], which a retry would only repeat.
     */
    internal suspend fun <K> getOrGenerate(
        alias: String,
        expected: ProtectedKeyAlgorithm,
        attach: (ReadBuffer) -> K,
        generate: () -> Pair<K, PlatformBuffer>,
        release: (K) -> Unit,
    ): K =
        withContext(keychainDispatcher) {
            migrateOnce()
            existing(alias, expected, attach) ?: addGenerated(alias, expected, attach, generate, release)
        }

    private fun <K> addGenerated(
        alias: String,
        expected: ProtectedKeyAlgorithm,
        attach: (ReadBuffer) -> K,
        generate: () -> Pair<K, PlatformBuffer>,
        release: (K) -> Unit,
    ): K {
        val (key, record) = generate()
        var added = false
        try {
            repeat(DUPLICATE_ATTEMPTS) {
                when (records.put(alias, record)) {
                    KeychainPut.Added -> {
                        added = true
                        return key
                    }
                    KeychainPut.AlreadyPresent -> existing(alias, expected, attach)?.let { return it }
                }
            }
            throw KeyStoreException.AccessDenied()
        } finally {
            record.freeNativeMemory()
            if (!added) release(key)
        }
    }

    /** The key [alias] holds, if its record is [expected]; another kind is an [KeyStoreException.AliasMismatch]. */
    private fun <K> existing(
        alias: String,
        expected: ProtectedKeyAlgorithm,
        attach: (ReadBuffer) -> K,
    ): K? =
        records.get(alias)?.let { record ->
            try {
                when (val stored = enclaveRecordAlgorithm(record)) {
                    expected -> attach(record)
                    else -> throw KeyStoreException.AliasMismatch(alias, stored, expected)
                }
            } finally {
                record.freeNativeMemory()
            }
        }

    override suspend fun loadSigning(alias: String): SigningKey? {
        requireValidAlias(alias)
        return loadRecord(alias, ProtectedKeyAlgorithm.EcdsaP256) { record ->
            provider.signingFromRecord(record, ProtectedKeySpec())
        }
    }

    override suspend fun loadAesGcm(alias: String): AesGcmKey? {
        requireValidAlias(alias)
        // No AES key is ever persisted here (see getOrGenerateAesGcm).
        return null
    }

    override suspend fun loadKeyAgreement(alias: String): KeyAgreementKeyPair? {
        requireValidAlias(alias)
        return loadRecord(alias, ProtectedKeyAlgorithm.EcdhP256) { record ->
            provider.keyAgreementFromRecord(record, ProtectedKeySpec())
        }
    }

    /**
     * The shared load path: reads the record under [alias] and re-attaches it through [attach] only
     * when its kind byte says [expected]. A record of the other kind answers `null` — the load
     * contract is "absent or a different kind", never a mistyped handle.
     */
    internal suspend fun <T> loadRecord(
        alias: String,
        expected: ProtectedKeyAlgorithm,
        attach: (ReadBuffer) -> T,
    ): T? =
        withContext(keychainDispatcher) {
            migrateOnce()
            records.get(alias)?.let { record ->
                try {
                    if (enclaveRecordAlgorithm(record) == expected) attach(record) else null
                } finally {
                    record.freeNativeMemory()
                }
            }
        }

    override suspend fun contains(alias: String): Boolean {
        requireValidAlias(alias)
        return withContext(keychainDispatcher) {
            migrateOnce()
            records.contains(alias)
        }
    }

    override suspend fun aliases(): Set<String> =
        withContext(keychainDispatcher) {
            migrateBeforeListing()
            records.aliases()
        }

    override suspend fun delete(alias: String): Boolean {
        requireValidAlias(alias)
        return withContext(keychainDispatcher) { records.delete(alias) }
    }

    override fun close() = Unit

    private val migrated = AtomicInt(0)

    /**
     * Moves this store's records written under an older protection class to the current one, until the move settles
     * (see [migrationStep]). Best effort before an ordinary operation: a device locked right now leaves the move for
     * the next call.
     */
    private fun migrateOnce() {
        if (migrated.load() == 1) return
        when (migrationStep(records.migrate())) {
            MigrationStep.Settled -> migrated.store(1)
            MigrationStep.TryAgain -> Unit
        }
    }

    /**
     * The same move before a listing, until it has once succeeded. While a record under the older class is withheld
     * by a locked device a listing would silently leave it out, so that, and only that, is
     * [KeyStoreException.DeviceLocked]; any other failure of the move is left for the next call and the listing goes
     * ahead.
     */
    private fun migrateBeforeListing() {
        if (migrated.load() == 1) return
        val status = records.migrate()
        if (status == BCKS_ERR_LOCKED) throw KeyStoreException.DeviceLocked()
        when (migrationStep(status)) {
            MigrationStep.Settled -> migrated.store(1)
            MigrationStep.TryAgain -> Unit
        }
    }

    private companion object {
        // Newline-joined account names: the first read's buffer. A listing that outgrows it is re-read
        // at the size the keychain reports.
        const val DEFAULT_ALIASES_CAPACITY = 4096

        // Adds tried when the keychain says a record is there that no read returns: once, then once more.
        const val DUPLICATE_ATTEMPTS = 2
    }
}

/** What adding a record found. */
internal sealed interface KeychainPut {
    data object Added : KeychainPut

    /** Another writer added a record under the alias first; it was left as it is. */
    data object AlreadyPresent : KeychainPut
}

/** One store's keychain records by alias: the shim in production ([ShimKeychainRecords]), a fake in tests. */
internal interface KeychainRecords {
    /** Adds [record] under [alias], never replacing one already there. */
    fun put(
        alias: String,
        record: ReadBuffer,
    ): KeychainPut

    /** The record under [alias] as a fresh read-ready buffer (the caller frees it), or `null` if there is none. */
    fun get(alias: String): PlatformBuffer?

    fun contains(alias: String): Boolean

    /** `true` if a record was removed, `false` if there was none. */
    fun delete(alias: String): Boolean

    fun aliases(): Set<String>

    /** Moves records under an older protection class to the current one; the shim's status. */
    fun migrate(): Int
}

/**
 * [KeychainRecords] through the CryptoKit shim's generic-password items: service = store [name], account = alias, both
 * passed as Kotlin Strings, which Kotlin/Native cinterop converts to the shim's `const char*` for the call.
 */
internal class ShimKeychainRecords(
    private val name: String,
    private val initialAliasesCapacity: Int,
) : KeychainRecords {
    override fun put(
        alias: String,
        record: ReadBuffer,
    ): KeychainPut {
        val recLen = record.remaining()
        var status = -1
        record.withRemainingBytes2(recLen) { recPtr ->
            status = bcks_keychain_put(name, alias, recPtr.reinterpret(), recLen.convert())
        }
        return when (status) {
            BCKS_OK -> KeychainPut.Added
            BCKS_ERR_DUPLICATE -> KeychainPut.AlreadyPresent
            else -> throw keychainFailure(status)
        }
    }

    override fun migrate(): Int = bcks_keychain_migrate(name)

    override fun get(alias: String): PlatformBuffer? {
        val out = BufferFactory.Default.allocate(RECORD_CAP)
        var status = -1
        var written = 0
        memScoped {
            val lenOut = alloc<size_tVar>()
            out.withWritablePointer(RECORD_CAP) { ptr ->
                status = bcks_keychain_get(name, alias, ptr.reinterpret(), RECORD_CAP.convert(), lenOut.ptr)
            }
            written = lenOut.value.toInt()
        }
        return when (status) {
            BCKS_OK -> {
                out.position(written)
                out.resetForRead()
                out
            }
            BCKS_ERR_INPUT -> {
                out.freeNativeMemory()
                null
            }
            else -> {
                out.freeNativeMemory()
                throw keychainFailure(status)
            }
        }
    }

    override fun contains(alias: String): Boolean =
        when (val status = bcks_keychain_contains(name, alias)) {
            1 -> true
            0 -> false
            else -> throw keychainFailure(status)
        }

    override fun delete(alias: String): Boolean =
        when (val status = bcks_keychain_delete(name, alias)) {
            BCKS_OK -> true
            BCKS_ERR_INPUT -> false
            else -> throw keychainFailure(status)
        }

    /**
     * Every account name under this store. Starts from [initialAliasesCapacity] and, when the names
     * outgrow it, retries with the size the keychain reported, so the listing is bounded by the item
     * count rather than a fixed cap. Items added between two reads only grow the next request.
     */
    override fun aliases(): Set<String> {
        var capacity = initialAliasesCapacity
        repeat(ALIASES_ATTEMPTS) {
            when (val listing = readAliases(capacity)) {
                is AliasListing.Read -> return listing.names
                is AliasListing.Needs -> capacity = listing.bytes
            }
        }
        throw KeyStoreException.StorageFailure(retryable = true)
    }

    private sealed interface AliasListing {
        class Read(
            val names: Set<String>,
        ) : AliasListing

        class Needs(
            val bytes: Int,
        ) : AliasListing
    }

    private fun readAliases(capacity: Int): AliasListing =
        memScoped {
            val out = allocArray<ByteVar>(capacity + 1)
            val lenOut = alloc<size_tVar>()
            when (val status = bcks_keychain_aliases(name, out.reinterpret(), capacity.convert(), lenOut.ptr)) {
                BCKS_OK -> {
                    val n = lenOut.value.toInt()
                    out[n] = 0.toByte() // NUL-terminate the newline-joined names for toKString
                    AliasListing.Read(out.toKString().split('\n').filterTo(mutableSetOf()) { it.isNotEmpty() })
                }
                BCKS_ERR_BUFFER -> AliasListing.Needs(maxOf(lenOut.value.toInt(), capacity * 2))
                else -> throw keychainFailure(status)
            }
        }

    private companion object {
        // A record is `u8 kind | u16 pointLen | point(65) | Enclave blob(~284)` — well under 4 KiB.
        const val RECORD_CAP = 4096

        // Reads before giving up on a listing that keeps growing between reads (a concurrent writer).
        const val ALIASES_ATTEMPTS = 4
    }
}
