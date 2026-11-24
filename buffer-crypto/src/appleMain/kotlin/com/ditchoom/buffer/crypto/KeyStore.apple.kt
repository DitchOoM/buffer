@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_BUFFER
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_CORRUPT
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_DENIED
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_DUPLICATE
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_INPUT
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
import kotlinx.coroutines.Dispatchers
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
 * entitlement, a retryable [KeyStoreException.StorageFailure] otherwise) and is probed again on the
 * next, so a device with an Enclave never ends up on a weaker or non-durable store.
 *
 * Only hardware with no Enclave at all (the simulator) uses an in-process, **non-durable** software
 * store ([KeyCustody.ExportableSoftware]) — honest about the weaker custody (a `requireTier` caller is
 * refused rather than silently upgraded), but keys there do not survive a process restart. A consumer
 * needing durability without the Enclave supplies [KeyStoreConfig.storage].
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
        EnclaveProbe.Refused, EnclaveProbe.NotPresent ->
            PlatformStoreOpening.Refused(KeyStoreException.StorageFailure(retryable = true))
    }

/** The failure an Enclave generate status names: a locked device is [KeyStoreException.DeviceLocked]. */
internal fun enclaveGenerateFailure(status: Int): CryptoException =
    when (status) {
        BCKS_ERR_LOCKED -> KeyStoreException.DeviceLocked()
        // The binary lacks the entitlement the Enclave requires: a definite answer, not a passing failure.
        BCKS_ERR_DENIED -> KeyStoreException.AccessDenied()
        else -> HardwareKeyException.SecureElementUnavailable()
    }

/**
 * The failure an Enclave sign status names. [BCKS_ERR_LOCKED] is a key whose class is unavailable while the device is
 * locked: an older key made under a when-unlocked class, which keeps that class for life.
 */
internal fun enclaveSignFailure(status: Int): CryptoException =
    when (status) {
        BCKS_ERR_INPUT -> HardwareKeyException.KeyInvalidated()
        BCKS_ERR_LOCKED -> KeyStoreException.DeviceLocked()
        else -> HardwareKeyException.TransientHardwareFailure(retryable = false)
    }

/**
 * The [KeyStoreException] a keychain status names. [BCKS_ERR_LOCKED] is the one a background launch on
 * a locked device meets, and is [KeyStoreException.DeviceLocked] so a caller can wait for the unlock.
 */
internal fun keychainFailure(status: Int): KeyStoreException =
    when (status) {
        BCKS_ERR_LOCKED -> KeyStoreException.DeviceLocked()
        BCKS_ERR_DENIED -> KeyStoreException.AccessDenied()
        BCKS_ERR_CORRUPT -> KeyStoreException.CorruptEntry()
        BCKS_ERR_RETRY, BCKS_ERR_DUPLICATE -> KeyStoreException.StorageFailure(retryable = true)
        // The process has no keychain at all (an extension or sandbox without one): retrying cannot help.
        BCKS_ERR_NO_KEYCHAIN -> KeyStoreException.StorageFailure(retryable = false)
        else -> KeyStoreException.StorageFailure(retryable = false)
    }

/**
 * A persistent, alias-addressable [KeyStore] whose keys are Secure Enclave P-256 signing keys
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
 */
internal class AppleKeychainKeyStore(
    private val provider: SecureEnclaveHardwareKeyProvider,
    private val name: String,
    private val initialAliasesCapacity: Int = DEFAULT_ALIASES_CAPACITY,
) : KeyStore {
    override fun custodyFor(alg: ProtectedKeyAlgorithm): KeyCustody = provider.custody

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
     * call generated is dropped — an Enclave key exists only through its record, so nothing of it is left behind.
     */
    private suspend fun <K> getOrGenerate(
        alias: String,
        expected: ProtectedKeyAlgorithm,
        attach: (ReadBuffer) -> K,
        generate: () -> Pair<K, PlatformBuffer>,
        release: (K) -> Unit,
    ): K =
        withContext(Dispatchers.Default) {
            migrateOnce()
            existing(alias, expected, attach) ?: run {
                val (key, record) = generate()
                val put =
                    try {
                        keychainPut(alias, record)
                    } finally {
                        record.freeNativeMemory()
                    }
                when (put) {
                    KeychainPut.Added -> key
                    KeychainPut.AlreadyPresent -> {
                        release(key)
                        existing(alias, expected, attach) ?: throw KeyStoreException.StorageFailure(retryable = true)
                    }
                }
            }
        }

    /** The key [alias] holds, if its record is [expected]; another kind is an [KeyStoreException.AliasMismatch]. */
    private fun <K> existing(
        alias: String,
        expected: ProtectedKeyAlgorithm,
        attach: (ReadBuffer) -> K,
    ): K? =
        keychainGet(alias)?.let { record ->
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
    private suspend fun <T> loadRecord(
        alias: String,
        expected: ProtectedKeyAlgorithm,
        attach: (ReadBuffer) -> T,
    ): T? =
        withContext(Dispatchers.Default) {
            migrateOnce()
            keychainGet(alias)?.let { record ->
                try {
                    if (enclaveRecordAlgorithm(record) == expected) attach(record) else null
                } finally {
                    record.freeNativeMemory()
                }
            }
        }

    override suspend fun contains(alias: String): Boolean {
        requireValidAlias(alias)
        return withContext(Dispatchers.Default) {
            migrateOnce()
            keychainContains(alias)
        }
    }

    override suspend fun aliases(): Set<String> =
        withContext(Dispatchers.Default) {
            migrateBeforeListing()
            keychainAliases()
        }

    override suspend fun delete(alias: String): Boolean {
        requireValidAlias(alias)
        return withContext(Dispatchers.Default) { keychainDelete(alias) }
    }

    override fun close() = Unit

    // --- Keychain IO (generic-password items via the CryptoKit shim) -------------------------------

    // service = store name, account = alias — both passed as Kotlin Strings, which Kotlin/Native
    // cinterop converts to the shim's `const char*` for the call (as with bcks_la_context_create).

    private sealed interface KeychainPut {
        data object Added : KeychainPut

        /** Another writer added a record under the alias first; it was left as it is. */
        data object AlreadyPresent : KeychainPut
    }

    private fun keychainPut(
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

    private val migrated = AtomicInt(0)

    /**
     * Moves this store's records written under an older protection class to the current one, once per store. Best
     * effort before an ordinary operation: a device locked right now leaves the move for the next call.
     */
    private fun migrateOnce() {
        if (migrated.load() == 1) return
        if (bcks_keychain_migrate(name) == BCKS_OK) migrated.store(1)
    }

    /**
     * The same move before a listing, until it has once succeeded. While a record under the older class is withheld
     * by a locked device a listing would silently leave it out, so that, and only that, is
     * [KeyStoreException.DeviceLocked]; any other failure of the move is left for the next call and the listing goes
     * ahead.
     */
    private fun migrateBeforeListing() {
        if (migrated.load() == 1) return
        when (val status = bcks_keychain_migrate(name)) {
            BCKS_OK -> migrated.store(1)
            BCKS_ERR_LOCKED -> throw KeyStoreException.DeviceLocked()
            else -> Unit
        }
    }

    /** The record stored under [alias] as a fresh read-ready buffer (caller frees it), or `null` if absent. */
    private fun keychainGet(alias: String): PlatformBuffer? {
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

    private fun keychainContains(alias: String): Boolean =
        when (val status = bcks_keychain_contains(name, alias)) {
            1 -> true
            0 -> false
            else -> throw keychainFailure(status)
        }

    private fun keychainDelete(alias: String): Boolean =
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
    private fun keychainAliases(): Set<String> {
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

        // Newline-joined account names: the first read's buffer. A listing that outgrows it is re-read
        // at the size the keychain reports.
        const val DEFAULT_ALIASES_CAPACITY = 4096

        // Reads before giving up on a listing that keeps growing between reads (a concurrent writer).
        const val ALIASES_ATTEMPTS = 4
    }
}
