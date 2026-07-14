@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_ERR_INPUT
import com.ditchoom.buffer.crypto.cinterop.cryptokit.BCKS_OK
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_aliases
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_contains
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_delete
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_get
import com.ditchoom.buffer.crypto.cinterop.cryptokit.bcks_keychain_put
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

/**
 * Apple: a durable [AppleKeychainKeyStore] where the Secure Enclave is present and usable — each
 * persistent key's private scalar lives in the Enclave ([KeyCustody.NonExportable.Hardware]) and the
 * Keychain durably holds its restore record keyed by (`kSecAttrService` = [KeyStoreConfig.name],
 * `kSecAttrAccount` = alias). Where the Enclave is unavailable (simulator, an unentitled CLI runner)
 * it falls back to an in-process software store ([KeyCustody.ExportableSoftware]) — honest about the
 * weaker custody, so a `requireTier` caller is refused rather than silently upgraded. A consumer may
 * force a software medium (e.g. an encrypted DB) via [KeyStoreConfig.storage].
 */
internal actual fun platformKeyStore(config: KeyStoreConfig): KeyStore =
    when {
        config.storage != null -> SoftwareKeyStore(config.storage)
        else ->
            appleEnclaveProviderOrNull()?.let { AppleKeychainKeyStore(it, config.name) }
                ?: SoftwareKeyStore(InMemoryKeyStorage())
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
 * Serves only what the Enclave backs non-exportably: ECDSA P-256 signing. A get-or-generate for
 * anything else (other schemes, AES-GCM, key agreement) throws
 * [HardwareKeyException.AlgorithmNotEligible] — consult [custodyFor] / [eligible] first, or supply a
 * software [KeyStoreConfig.storage] for a persistent key the Enclave cannot hold.
 */
internal class AppleKeychainKeyStore(
    private val provider: SecureEnclaveHardwareKeyProvider,
    private val name: String,
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
        return withContext(Dispatchers.Default) {
            keychainGet(alias)?.let { record ->
                try {
                    // Only Enclave P-256 signing keys are ever persisted here, so a present record is
                    // always a signing key of the requested scheme — no AliasMismatch is reachable.
                    provider.signingFromRecord(record, spec)
                } finally {
                    record.freeNativeMemory()
                }
            } ?: run {
                val (key, record) = provider.generatePersistentSigning(spec)
                try {
                    keychainPut(alias, record)
                } finally {
                    record.freeNativeMemory()
                }
                key
            }
        }
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
        // The Secure Enclave backs no app-controlled ECDH / X25519 agreement key.
        throw HardwareKeyException.AlgorithmNotEligible()
    }

    override suspend fun loadSigning(alias: String): SigningKey? {
        requireValidAlias(alias)
        return withContext(Dispatchers.Default) {
            keychainGet(alias)?.let { record ->
                try {
                    provider.signingFromRecord(record, ProtectedKeySpec())
                } finally {
                    record.freeNativeMemory()
                }
            }
        }
    }

    override suspend fun loadAesGcm(alias: String): AesGcmKey? {
        requireValidAlias(alias)
        // No AES key is ever persisted here (see getOrGenerateAesGcm).
        return null
    }

    override suspend fun loadKeyAgreement(alias: String): KeyAgreementKeyPair? {
        requireValidAlias(alias)
        // No agreement key is ever persisted here (see getOrGenerateKeyAgreement).
        return null
    }

    override suspend fun contains(alias: String): Boolean {
        requireValidAlias(alias)
        return withContext(Dispatchers.Default) { keychainContains(alias) }
    }

    override suspend fun aliases(): Set<String> = withContext(Dispatchers.Default) { keychainAliases() }

    override suspend fun delete(alias: String): Boolean {
        requireValidAlias(alias)
        return withContext(Dispatchers.Default) { keychainDelete(alias) }
    }

    override fun close() = Unit

    // --- Keychain IO (generic-password items via the CryptoKit shim) -------------------------------

    // service = store name, account = alias — both passed as Kotlin Strings, which Kotlin/Native
    // cinterop converts to the shim's `const char*` for the call (as with bcks_la_context_create).

    private fun keychainPut(
        alias: String,
        record: ReadBuffer,
    ) {
        val recLen = record.remaining()
        var status = -1
        record.withRemainingBytes2(recLen) { recPtr ->
            status = bcks_keychain_put(name, alias, recPtr.reinterpret(), recLen.convert())
        }
        if (status != BCKS_OK) throw KeyStoreException.StorageFailure(retryable = false)
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
                throw KeyStoreException.StorageFailure(retryable = false)
            }
        }
    }

    private fun keychainContains(alias: String): Boolean =
        when (bcks_keychain_contains(name, alias)) {
            1 -> true
            0 -> false
            else -> throw KeyStoreException.StorageFailure(retryable = false)
        }

    private fun keychainDelete(alias: String): Boolean =
        when (bcks_keychain_delete(name, alias)) {
            BCKS_OK -> true
            BCKS_ERR_INPUT -> false
            else -> throw KeyStoreException.StorageFailure(retryable = false)
        }

    private fun keychainAliases(): Set<String> =
        memScoped {
            val out = allocArray<ByteVar>(ALIASES_CAP + 1)
            val lenOut = alloc<size_tVar>()
            val status = bcks_keychain_aliases(name, out.reinterpret(), ALIASES_CAP.convert(), lenOut.ptr)
            if (status != BCKS_OK) throw KeyStoreException.StorageFailure(retryable = false)
            val n = lenOut.value.toInt()
            out[n] = 0.toByte() // NUL-terminate the newline-joined names for toKString
            out.toKString().split('\n').filterTo(mutableSetOf()) { it.isNotEmpty() }
        }

    private companion object {
        // A record is `u16 pointLen | point(65) | Enclave blob(~284)` — well under 4 KiB.
        const val RECORD_CAP = 4096

        // Newline-joined account names; a device identity store holds few keys, so 64 KiB is ample.
        const val ALIASES_CAP = 65536
    }
}
