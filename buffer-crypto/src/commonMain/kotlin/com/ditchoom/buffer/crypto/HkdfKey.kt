package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

/*
 * HKDF-SHA256 key-derivation keys.
 *
 * [Hkdf] derives from input keying material the caller holds as bytes. An [HkdfKey] holds that
 * material as a key instead, so a key hierarchy (a root, children derived from it, AEAD keys at the
 * leaves) can be walked without the root ever being readable again. On js/wasmJs the root is a
 * non-extractable WebCrypto `CryptoKey` that can be persisted in IndexedDB by [HkdfKeyStore] and
 * reopened from any context of the same origin, a window or a worker alike. Elsewhere it is an
 * in-memory software key with the same derivation results, so one code path serves every target.
 *
 * Every derivation is HKDF-SHA256 (RFC 5869) with this key as the input keying material, and is
 * byte-for-byte what [Hkdf.derive] produces from the same bytes: a key derived here interoperates
 * with a peer that holds the raw material.
 */

/** The size of a key-derivation key [HkdfKey.deriveHkdfKey] produces unless told otherwise: one SHA-256 block. */
const val HKDF_KEY_DEFAULT_BYTES: Int = HMAC_SHA256_BYTES

/** The smallest input keying material an [HkdfKey] accepts: 128 bits. */
const val HKDF_KEY_MIN_BYTES: Int = 16

/** The largest output one HKDF-SHA256 expansion can produce (RFC 5869 §2.3). */
const val HKDF_SHA256_MAX_OUTPUT_BYTES: Int = HKDF_MAX_BLOCKS * HMAC_SHA256_BYTES

/**
 * HKDF-SHA256 input keying material held as a key. Derives bytes, child key-derivation keys, and
 * AES-GCM keys; never returns its own material.
 *
 * Child keys keep this key's [custody]: a child of a non-exportable WebCrypto key is itself a
 * non-extractable `CryptoKey`, and its bytes are never handed to Kotlin. (WebCrypto cannot derive an
 * HKDF key directly, so the child's bits are derived and imported inside the JS engine and the
 * transient copy is zeroed there.) Only [deriveBytes] returns derived material to the caller.
 *
 * `sealed`: the implementations are module-internal, as for [AesGcmKey].
 */
sealed interface HkdfKey : AutoCloseable {
    /** Where this key's material lives and whether the process can read it. */
    val custody: KeyCustody

    /** Length of the input keying material in bytes. */
    val sizeBytes: Int

    /**
     * HKDF-SHA256(salt, this, info) truncated to [length] bytes, as a read-ready buffer from
     * [factory]. Pass a secure deterministic factory when the output is itself secret.
     */
    suspend fun deriveBytes(
        salt: Salt,
        info: Info,
        length: Int,
        factory: BufferFactory = BufferFactory.Default,
    ): PlatformBuffer

    /** A child key-derivation key whose material is HKDF-SHA256(salt, this, info) of [sizeBytes] bytes. */
    suspend fun deriveHkdfKey(
        salt: Salt,
        info: Info,
        sizeBytes: Int = HKDF_KEY_DEFAULT_BYTES,
    ): HkdfKey

    /**
     * An AES-GCM key whose material is HKDF-SHA256(salt, this, info) of `sizeBits / 8` bytes. The key
     * equals `AesGcmKey.of(Hkdf.derive(salt, ikm, info, sizeBits / 8))`, so either side of a channel
     * may hold the raw form.
     */
    suspend fun deriveAesGcmKey(
        salt: Salt,
        info: Info,
        sizeBits: Int = AES_256_KEY_BYTES * Byte.SIZE_BITS,
    ): DerivedAesGcmKey

    companion object {
        /**
         * Wraps [ikm]'s remaining bytes as an in-memory key ([KeyCustody.ExportableSoftware]). The bytes
         * are copied into a buffer from [factory], secure and deterministic by default, so [close] wipes
         * them. For a non-exportable key on the web, use [CryptoCapabilities.importHkdfKey].
         */
        fun of(
            ikm: ReadBuffer,
            factory: BufferFactory = BufferFactory.deterministicSecure(),
        ): HkdfKey {
            requireHkdfKeySize(ikm.remaining())
            return InMemoryHkdfKey(copyBuffer(ikm, factory))
        }
    }
}

/**
 * An AES-GCM key derived from an [HkdfKey], used with a caller-supplied nonce.
 *
 * The explicit-nonce ops of [AeadAsyncOps] are bound to in-memory keys because a secure element mints
 * its own IV and cannot honour one. A derived key can: WebCrypto takes the IV from the caller for a
 * non-extractable key too. Uniqueness of each (key, nonce) pair is the caller's contract, exactly as
 * for [AeadAsyncOps.sealWithNonce]; a repeated pair under GCM is catastrophic.
 *
 * Output is bare `ciphertext ‖ tag`, byte-identical to [AeadAsyncOps.sealWithNonce] under the same key
 * bytes, nonce and AAD.
 */
sealed interface DerivedAesGcmKey : AutoCloseable {
    /** 128 or 256. */
    val sizeBits: Int

    /** Where this key's material lives and whether the process can read it. */
    val custody: KeyCustody

    /** Seals [plaintext] under [nonce] (exactly [AEAD_NONCE_BYTES] bytes), returning `ciphertext ‖ tag`. */
    suspend fun sealWithNonce(
        nonce: ReadBuffer,
        plaintext: ReadBuffer,
        aad: Aad = Aad.None,
        factory: BufferFactory = BufferFactory.Default,
    ): PlatformBuffer

    /**
     * Opens `ciphertext ‖ tag` sealed under [nonce], returning the plaintext only if the tag verifies.
     *
     * @throws VerificationFailed if authentication fails, opaque as everywhere in this module.
     */
    suspend fun openWithNonce(
        nonce: ReadBuffer,
        ciphertextAndTag: ReadBuffer,
        aad: Aad = Aad.None,
        factory: BufferFactory = BufferFactory.Default,
    ): PlatformBuffer
}

/**
 * A persistent, alias-addressable store of [HkdfKey]s. On js/wasmJs with IndexedDB the keys are
 * non-extractable `CryptoKey`s ([KeyCustody.NonExportable.Software]) kept in the IndexedDB database
 * named by [KeyStoreConfig.name]; any window or worker of the same origin that opens a store of that
 * name sees the same keys. Elsewhere the keys are software blobs ([KeyCustody.ExportableSoftware]) in
 * the platform's software medium, or in [KeyStoreConfig.storage] when one is supplied.
 *
 * Keep the name distinct from a [KeyStore]'s: the two stores share a record format but not an
 * algorithm set.
 */
interface HkdfKeyStore : AutoCloseable {
    /** The custody every key of this store has. */
    val custody: KeyCustody

    /**
     * Stores [ikm]'s remaining bytes under [alias] and returns the stored key. The store never replaces
     * a key: an occupied [alias] throws [KeyStoreException.AliasOccupied], and replacing is an explicit
     * [delete] first. On the web the check and the write are one IndexedDB request, so of two contexts
     * importing under one alias exactly one succeeds; a software store over a [KeyStorage] has no such
     * guarantee beyond what its medium gives.
     *
     * The caller still holds [ikm] and should wipe it once imported.
     */
    suspend fun importKey(
        alias: String,
        ikm: ReadBuffer,
    ): HkdfKey

    /** Reopens the key under [alias], or `null` if [alias] is absent or holds something other than an HKDF key. */
    suspend fun load(alias: String): HkdfKey?

    /** Whether any entry is stored under [alias]. */
    suspend fun contains(alias: String): Boolean

    /** Every alias stored in this store. */
    suspend fun aliases(): Set<String>

    /** Deletes [alias]; `true` if an entry was removed. Keys already opened from it keep working until closed. */
    suspend fun delete(alias: String): Boolean
}

/** Default [KeyStoreConfig.name] for [CryptoCapabilities.hkdfKeyStore], distinct from the [KeyStore] default. */
const val DEFAULT_HKDF_KEY_STORE_NAME: String = "buffer-crypto-hkdf"

/** The device-persistent [HkdfKeyStore] for this platform. See [HkdfKeyStore] for the custody per target. */
fun CryptoCapabilities.hkdfKeyStore(config: KeyStoreConfig = KeyStoreConfig(name = DEFAULT_HKDF_KEY_STORE_NAME)): HkdfKeyStore =
    platformHkdfKeyStore(config)

/**
 * Imports [ikm]'s remaining bytes as a key that is not persisted: a non-extractable WebCrypto key on
 * js/wasmJs where `crypto.subtle` is present, otherwise the same in-memory key as [HkdfKey.of]. The
 * caller still holds [ikm] and should wipe it once imported.
 */
suspend fun CryptoCapabilities.importHkdfKey(ikm: ReadBuffer): HkdfKey {
    requireHkdfKeySize(ikm.remaining())
    return platformImportHkdfKey(ikm)
}

internal expect fun platformHkdfKeyStore(config: KeyStoreConfig): HkdfKeyStore

internal expect suspend fun platformImportHkdfKey(ikm: ReadBuffer): HkdfKey

internal fun requireHkdfKeySize(sizeBytes: Int) =
    require(sizeBytes in HKDF_KEY_MIN_BYTES..HKDF_SHA256_MAX_OUTPUT_BYTES) {
        "an HKDF key must be $HKDF_KEY_MIN_BYTES..$HKDF_SHA256_MAX_OUTPUT_BYTES bytes, was $sizeBytes"
    }

internal fun requireHkdfOutputLength(length: Int) =
    require(length in 1..HKDF_SHA256_MAX_OUTPUT_BYTES) {
        "HKDF-SHA256 output must be 1..$HKDF_SHA256_MAX_OUTPUT_BYTES bytes, was $length"
    }

internal fun requireAesKeySizeBits(sizeBits: Int) =
    require(sizeBits == AES_128_KEY_BYTES * Byte.SIZE_BITS || sizeBits == AES_256_KEY_BYTES * Byte.SIZE_BITS) {
        "AES-GCM key must be ${AES_128_KEY_BYTES * Byte.SIZE_BITS} or ${AES_256_KEY_BYTES * Byte.SIZE_BITS} bits, was $sizeBits"
    }

// =============================================================================
// In-memory implementation (every target; the web's fallback without crypto.subtle)
// =============================================================================

internal class InMemoryHkdfKey(
    private val material: PlatformBuffer,
) : HkdfKey {
    override val custody: KeyCustody get() = KeyCustody.ExportableSoftware
    override val sizeBytes: Int = material.remaining()
    private var closed = false

    override suspend fun deriveBytes(
        salt: Salt,
        info: Info,
        length: Int,
        factory: BufferFactory,
    ): PlatformBuffer {
        requireHkdfOutputLength(length)
        val out = factory.allocate(length)
        Hkdf.deriveInto(salt.view(), requireOpen().slice(), info.view(), length, out)
        out.resetForRead()
        return out
    }

    override suspend fun deriveHkdfKey(
        salt: Salt,
        info: Info,
        sizeBytes: Int,
    ): HkdfKey {
        requireHkdfKeySize(sizeBytes)
        return InMemoryHkdfKey(deriveBytes(salt, info, sizeBytes, secureScratch))
    }

    override suspend fun deriveAesGcmKey(
        salt: Salt,
        info: Info,
        sizeBits: Int,
    ): DerivedAesGcmKey {
        requireAesKeySizeBits(sizeBits)
        val bytes = deriveBytes(salt, info, sizeBits / Byte.SIZE_BITS, secureScratch)
        try {
            return InMemoryDerivedAesGcmKey(AesGcmKey.of(bytes))
        } finally {
            bytes.freeNativeMemory()
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            material.freeNativeMemory()
        }
    }

    /** The material as an encodable read view, for a software store to persist. */
    fun requireOpen(): PlatformBuffer {
        check(!closed) { "HkdfKey already closed" }
        return material
    }
}

internal class InMemoryDerivedAesGcmKey(
    private val key: SyncCapableAesGcmKey,
) : DerivedAesGcmKey {
    override val sizeBits: Int get() = key.sizeBits
    override val custody: KeyCustody get() = KeyCustody.ExportableSoftware

    override suspend fun sealWithNonce(
        nonce: ReadBuffer,
        plaintext: ReadBuffer,
        aad: Aad,
        factory: BufferFactory,
    ): PlatformBuffer {
        requireExplicitNonce(nonce)
        return aesGcmSealWithNonceAsync(key, nonce, aad.bytesOrNull, plaintext, factory)
    }

    override suspend fun openWithNonce(
        nonce: ReadBuffer,
        ciphertextAndTag: ReadBuffer,
        aad: Aad,
        factory: BufferFactory,
    ): PlatformBuffer {
        requireExplicitNonce(nonce)
        requireCiphertextAndTag(ciphertextAndTag)
        return aesGcmOpenWithNonceAsync(key, nonce, aad.bytesOrNull, ciphertextAndTag, factory)
    }

    override fun close() = key.close()
}

/**
 * A non-exportable [HkdfKey]: an opaque handle whose derivations run through provider-supplied gated
 * closures (WebCrypto on the web). Arguments are validated here, once, before any closure runs.
 */
internal class ProtectedHkdfKey(
    override val sizeBytes: Int,
    override val custody: KeyCustody.NonExportable,
    private val gatedDeriveBytes: suspend (salt: Salt, info: Info, length: Int, factory: BufferFactory) -> PlatformBuffer,
    private val gatedDeriveHkdfKey: suspend (salt: Salt, info: Info, sizeBytes: Int) -> HkdfKey,
    private val gatedDeriveAesGcmKey: suspend (salt: Salt, info: Info, sizeBits: Int) -> DerivedAesGcmKey,
    private val onClose: () -> Unit,
) : HkdfKey {
    override suspend fun deriveBytes(
        salt: Salt,
        info: Info,
        length: Int,
        factory: BufferFactory,
    ): PlatformBuffer {
        requireHkdfOutputLength(length)
        return gatedDeriveBytes(salt, info, length, factory)
    }

    override suspend fun deriveHkdfKey(
        salt: Salt,
        info: Info,
        sizeBytes: Int,
    ): HkdfKey {
        requireHkdfKeySize(sizeBytes)
        return gatedDeriveHkdfKey(salt, info, sizeBytes)
    }

    override suspend fun deriveAesGcmKey(
        salt: Salt,
        info: Info,
        sizeBits: Int,
    ): DerivedAesGcmKey {
        requireAesKeySizeBits(sizeBits)
        return gatedDeriveAesGcmKey(salt, info, sizeBits)
    }

    override fun close() = onClose()
}

/** A non-exportable [DerivedAesGcmKey] whose seal/open run through provider-supplied gated closures. */
internal class ProtectedDerivedAesGcmKey(
    override val sizeBits: Int,
    override val custody: KeyCustody.NonExportable,
    private val gatedSeal: suspend (nonce: ReadBuffer, plaintext: ReadBuffer, aad: Aad, factory: BufferFactory) -> PlatformBuffer,
    private val gatedOpen: suspend (nonce: ReadBuffer, ciphertextAndTag: ReadBuffer, aad: Aad, factory: BufferFactory) -> PlatformBuffer,
    private val onClose: () -> Unit,
) : DerivedAesGcmKey {
    override suspend fun sealWithNonce(
        nonce: ReadBuffer,
        plaintext: ReadBuffer,
        aad: Aad,
        factory: BufferFactory,
    ): PlatformBuffer {
        requireExplicitNonce(nonce)
        return gatedSeal(nonce, plaintext, aad, factory)
    }

    override suspend fun openWithNonce(
        nonce: ReadBuffer,
        ciphertextAndTag: ReadBuffer,
        aad: Aad,
        factory: BufferFactory,
    ): PlatformBuffer {
        requireExplicitNonce(nonce)
        requireCiphertextAndTag(ciphertextAndTag)
        return gatedOpen(nonce, ciphertextAndTag, aad, factory)
    }

    override fun close() = onClose()
}

/** The salt over a fresh read view, so a caller's buffer position is never moved. */
internal fun Salt.view(): Salt =
    when (this) {
        Salt.None -> Salt.None
        is Salt.Of -> Salt.Of(bytes.slice())
    }

/** The info over a fresh read view, so a caller's buffer position is never moved. */
internal fun Info.view(): Info =
    when (this) {
        Info.None -> Info.None
        is Info.Of -> Info.Of(bytes.slice())
    }

// =============================================================================
// Software store over a KeyStorage medium
// =============================================================================

internal class SoftwareHkdfKeyStore(
    private val storage: KeyStorage,
) : HkdfKeyStore {
    override val custody: KeyCustody get() = KeyCustody.ExportableSoftware

    override suspend fun importKey(
        alias: String,
        ikm: ReadBuffer,
    ): HkdfKey {
        requireValidAlias(alias)
        requireHkdfKeySize(ikm.remaining())
        if (storage.get(alias) != null) throw KeyStoreException.AliasOccupied(alias)
        val key = InMemoryHkdfKey(copyBuffer(ikm, secureScratch))
        val blob = encodeBlob(KIND_HKDF, 0, key.requireOpen(), emptyBuffer, secureScratch)
        try {
            storage.put(alias, blob)
        } finally {
            blob.freeNativeMemory()
        }
        return key
    }

    override suspend fun load(alias: String): HkdfKey? {
        requireValidAlias(alias)
        val decoded = storage.get(alias)?.let { decodeBlob(it) } ?: return null
        if (decoded.kind != KIND_HKDF) return null
        return InMemoryHkdfKey(copyBuffer(decoded.privateEncoded, secureScratch))
    }

    override suspend fun contains(alias: String): Boolean {
        requireValidAlias(alias)
        return storage.get(alias) != null
    }

    override suspend fun aliases(): Set<String> = storage.aliases()

    override suspend fun delete(alias: String): Boolean {
        requireValidAlias(alias)
        return storage.delete(alias)
    }

    override fun close() = Unit

    private companion object {
        val emptyBuffer: ReadBuffer = BufferFactory.Default.allocate(0).also { it.resetForRead() }
    }
}
