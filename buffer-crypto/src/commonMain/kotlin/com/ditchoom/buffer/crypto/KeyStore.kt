package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

/**
 * A persistent, alias-addressable key store. Where [KeyProvider.generateSigning] et al. mint
 * **ephemeral** keys (a random internal alias, auto-deleted on close), the alias methods here
 * **persist** — a key obtained through [getOrGenerateSigning] / [loadSigning] survives `close()` and
 * process restart until it is explicitly [delete]d.
 *
 * `KeyStore` **is a** [KeyProvider], so the whole custody-tier machinery applies unchanged: query the
 * custody you actually get with [KeyProvider.custodyFor], assert a minimum with
 * [KeyProvider.requireTier]. Custody is per platform — on-disk DER is [KeyCustody.ExportableSoftware]
 * (JVM / Linux), a non-extractable WebCrypto CryptoKey in IndexedDB is
 * [KeyCustody.NonExportable.Software] (JS / WASM), a keystore alias / Keychain tag is
 * [KeyCustody.NonExportable.Hardware] (Android / Apple).
 *
 * **Regenerate** is [delete] followed by a `getOrGenerate*` call — an honest two-step, since no
 * platform offers an atomic replace.
 *
 * Aliases are caller-chosen, non-secret names restricted to a portable subset (`[A-Za-z0-9._-]`,
 * 1..255 chars) so they behave identically across a filesystem, the Keychain, and IndexedDB; an
 * out-of-range alias throws [IllegalArgumentException].
 */
interface KeyStore :
    KeyProvider,
    AutoCloseable {
    /**
     * Idempotent get-or-generate for a signing key. If [alias] already holds a signing key of
     * [scheme], re-attaches to it; if it holds a key of a different kind or scheme, throws
     * [KeyStoreException.AliasMismatch]; otherwise generates a fresh signing key, persists it under
     * [alias], and returns it.
     */
    suspend fun getOrGenerateSigning(
        alias: String,
        scheme: SignatureScheme,
        spec: ProtectedKeySpec = ProtectedKeySpec(),
    ): SigningKey

    /** Idempotent get-or-generate for an AES-GCM key. See [getOrGenerateSigning] for the alias contract. */
    suspend fun getOrGenerateAesGcm(
        alias: String,
        spec: ProtectedKeySpec = ProtectedKeySpec(),
    ): AesGcmKey

    /** Idempotent get-or-generate for a key-agreement key pair on [curve]. See [getOrGenerateSigning]. */
    suspend fun getOrGenerateKeyAgreement(
        alias: String,
        curve: KeyAgreementCurve,
        spec: ProtectedKeySpec = ProtectedKeySpec(),
    ): KeyAgreementKeyPair

    /**
     * Re-attaches to a signing key persisted in a previous launch, or `null` if [alias] is absent
     * **or** holds a key of a different kind (a signing lookup over an AES alias is `null`, not an
     * error).
     */
    suspend fun loadSigning(alias: String): SigningKey?

    /** Re-attaches to a persisted AES-GCM key, or `null` if absent / a different kind. See [loadSigning]. */
    suspend fun loadAesGcm(alias: String): AesGcmKey?

    /** Re-attaches to a persisted key-agreement key pair, or `null` if absent / a different kind. */
    suspend fun loadKeyAgreement(alias: String): KeyAgreementKeyPair?

    /** Whether any key is stored under [alias]. */
    suspend fun contains(alias: String): Boolean

    /** Every alias currently stored in this store. */
    suspend fun aliases(): Set<String>

    /** Deletes the key under [alias]; `true` if one was removed, `false` if none existed. */
    suspend fun delete(alias: String): Boolean
}

/**
 * Per-platform key-store configuration, carried by value so the shape is identical on every target.
 */
class KeyStoreConfig(
    /**
     * Namespace for this store: the IndexedDB database name (web), the Keychain service (Apple), and
     * an alias / directory prefix (Android Keystore, JVM on-disk). Lets independent stores coexist.
     * Restricted to the same portable subset as an alias.
     */
    val name: String = "buffer-crypto",
    /**
     * JVM / Android on-disk directory for the default file store. `null` selects a per-user default
     * (`<user.home>/.buffer-crypto/<name>`, created owner-only). Ignored when [storage] is supplied
     * and on OS-backed tiers (Apple / web).
     */
    val location: String? = null,
    /**
     * Software-tier persistence medium. `null` selects the platform default on-disk store. Supply a
     * [KeyStorage] to redirect exportable PKCS#8 into your own durable store (an encrypted DB, a
     * secret manager). **Ignored on OS-backed tiers** (Apple / web / a future hardware Android),
     * whose keys never become an exportable blob — there is nothing for a blob store to hold.
     */
    val storage: KeyStorage? = null,
) {
    init {
        requireValidKeyStoreName(name)
    }
}

/**
 * The pluggable persistence medium under a **software (exportable)** [KeyStore]. It sees only opaque,
 * already-encoded bytes under a caller-chosen alias — never key semantics — so any durable named-blob
 * store qualifies. The bytes handed to [put] are **secret** private-key material: do not log them,
 * and wipe them on [delete]. Ignored on OS-backed tiers, where no exportable blob exists.
 */
interface KeyStorage {
    /** Stores [pkcs8]'s remaining bytes under [alias], replacing any existing value. */
    suspend fun put(
        alias: String,
        pkcs8: ReadBuffer,
    )

    /** The bytes stored under [alias] as a fresh read-ready buffer, or `null` if none. */
    suspend fun get(alias: String): ReadBuffer?

    /** Removes [alias]; `true` if a value was removed. */
    suspend fun delete(alias: String): Boolean

    /** Every alias currently stored. */
    suspend fun aliases(): Set<String>
}

/**
 * An in-process [KeyStorage] backed by a map — not durable across process restarts. The default
 * medium on platforms whose durable software store is not yet wired, and a convenient medium for
 * tests. Stored bytes are copied on [put] so the caller may reuse their buffer.
 */
class InMemoryKeyStorage : KeyStorage {
    private val entries = mutableMapOf<String, ReadBuffer>()

    override suspend fun put(
        alias: String,
        pkcs8: ReadBuffer,
    ) {
        entries[alias] = copyBuffer(pkcs8, BufferFactory.Default)
    }

    override suspend fun get(alias: String): ReadBuffer? = entries[alias]?.let { copyBuffer(it, BufferFactory.Default) }

    override suspend fun delete(alias: String): Boolean = entries.remove(alias) != null

    override suspend fun aliases(): Set<String> = entries.keys.toSet()
}

/**
 * The device-persistent key store for this platform. Total and non-null — every platform can persist.
 * Inspect the custody you actually get with [KeyProvider.custodyFor], or assert a floor with
 * [KeyProvider.requireTier]:
 *
 * ```kotlin
 * // Fails loudly on JVM/web instead of silently persisting a weaker identity key.
 * val identity = CryptoCapabilities.keyStore()
 *     .requireTier(ProtectedKeyAlgorithm.EcdsaP256, CustodyTier.Hardware)
 *     .getOrGenerateSigning("device-identity", SignatureScheme.EcdsaP256)
 * val spki = identity.verifyKey.exportSpki()   // publish the device's public identity
 * ```
 */
fun CryptoCapabilities.keyStore(config: KeyStoreConfig = KeyStoreConfig()): KeyStore = platformKeyStore(config)

/**
 * The platform key store. `internal expect`/`actual` so the implementation stays out of the public
 * ABI while [keyStore] keeps a stable commonMain signature — mirroring the
 * [platformProtectedKeyProvider] seam.
 */
internal expect fun platformKeyStore(config: KeyStoreConfig): KeyStore

// =============================================================================
// Alias / name validation — a portable subset that behaves the same on FS / Keychain / IndexedDB
// =============================================================================

private val KEY_STORE_NAME_REGEX = Regex("[A-Za-z0-9._-]{1,255}")

internal fun requireValidAlias(alias: String) =
    require(KEY_STORE_NAME_REGEX.matches(alias)) {
        "key alias must match [A-Za-z0-9._-]{1,255}, was '$alias'"
    }

internal fun requireValidKeyStoreName(name: String) =
    require(KEY_STORE_NAME_REGEX.matches(name)) {
        "key store name must match [A-Za-z0-9._-]{1,255}, was '$name'"
    }

// =============================================================================
// SoftwareKeyStore — commonMain ExportableSoftware store over a KeyStorage medium
// =============================================================================
//
// The write-once persistence logic a consumer would otherwise hand-roll per platform: it generates
// exportable in-memory keys (reusing SoftwareKeyProvider), persists each key's exported encodings
// through the pluggable KeyStorage medium under a self-describing blob, and reconstructs the sealed
// key type on load. Custody is ExportableSoftware — the DER blob is, by definition, exportable.

/** Persisted-key kinds, tagged in the on-blob header so a lookup of the wrong kind is detectable. */
private const val KIND_SIGNING = 0
private const val KIND_AES_GCM = 1
private const val KIND_AGREEMENT = 2
private const val BLOB_VERSION = 1

private fun signingTag(scheme: SignatureScheme): Int =
    when (scheme) {
        SignatureScheme.Ed25519 -> 0
        SignatureScheme.EcdsaP256 -> 1
        SignatureScheme.EcdsaP384 -> 2
        SignatureScheme.EcdsaP521 -> 3
    }

private fun signingScheme(tag: Int): SignatureScheme =
    when (tag) {
        0 -> SignatureScheme.Ed25519
        1 -> SignatureScheme.EcdsaP256
        2 -> SignatureScheme.EcdsaP384
        3 -> SignatureScheme.EcdsaP521
        else -> throw KeyStoreException.CorruptEntry()
    }

private fun agreementTag(curve: KeyAgreementCurve): Int =
    when (curve) {
        KeyAgreementCurve.X25519 -> 0
        KeyAgreementCurve.P256 -> 1
        KeyAgreementCurve.P384 -> 2
        KeyAgreementCurve.P521 -> 3
    }

private fun agreementCurve(tag: Int): KeyAgreementCurve =
    when (tag) {
        0 -> KeyAgreementCurve.X25519
        1 -> KeyAgreementCurve.P256
        2 -> KeyAgreementCurve.P384
        3 -> KeyAgreementCurve.P521
        else -> throw KeyStoreException.CorruptEntry()
    }

/** The [ProtectedKeyAlgorithm] a stored (kind, tag) header describes — for [KeyStoreException.AliasMismatch]. */
private fun storedAlgorithm(
    kind: Int,
    tag: Int,
): ProtectedKeyAlgorithm =
    when (kind) {
        KIND_SIGNING -> signingScheme(tag).toProtectedKeyAlgorithm()
        KIND_AES_GCM -> ProtectedKeyAlgorithm.AesGcm
        KIND_AGREEMENT -> agreementCurve(tag).toProtectedKeyAlgorithm()
        else -> throw KeyStoreException.CorruptEntry()
    }

/** A decoded blob header plus the two length-delimited body fields (read-ready slices). */
private class DecodedBlob(
    val kind: Int,
    val tag: Int,
    val privateEncoded: ReadBuffer,
    val publicEncoded: ReadBuffer,
)

/** `version | kind | tag | u16 privLen | priv | u16 pubLen | pub`. */
private fun encodeBlob(
    kind: Int,
    tag: Int,
    privateEncoded: ReadBuffer,
    publicEncoded: ReadBuffer,
): PlatformBuffer {
    val privLen = privateEncoded.remaining()
    val pubLen = publicEncoded.remaining()
    val out = BufferFactory.Default.allocate(BLOB_HEADER_BYTES + privLen + pubLen)
    out.writeByte(BLOB_VERSION.toByte())
    out.writeByte(kind.toByte())
    out.writeByte(tag.toByte())
    out.writeShort(privLen.toShort())
    out.writePreservingPosition(privateEncoded)
    out.writeShort(pubLen.toShort())
    out.writePreservingPosition(publicEncoded)
    out.resetForRead()
    return out
}

/** Appends [src]'s remaining bytes without advancing [src]'s position (it may be a live key buffer). */
private fun PlatformBuffer.writePreservingPosition(src: ReadBuffer) {
    if (src.remaining() == 0) return
    val mark = src.position()
    write(src)
    src.position(mark)
}

private const val BLOB_HEADER_BYTES = 3 + 2 + 2 // version+kind+tag + two u16 length prefixes

private fun decodeBlob(blob: ReadBuffer): DecodedBlob {
    if (blob.readByte().toInt() != BLOB_VERSION) throw KeyStoreException.CorruptEntry()
    val kind = blob.readByte().toInt()
    val tag = blob.readByte().toInt()
    val privLen = blob.readShort().toInt() and 0xFFFF
    val priv = blob.readBytes(privLen)
    val pubLen = blob.readShort().toInt() and 0xFFFF
    val pub = blob.readBytes(pubLen)
    return DecodedBlob(kind, tag, priv, pub)
}

internal class SoftwareKeyStore(
    private val storage: KeyStorage,
) : KeyStore {
    // --- KeyProvider (ephemeral) — delegate to the shared software floor; custody is ExportableSoftware.
    override fun custodyFor(alg: ProtectedKeyAlgorithm): KeyCustody = KeyCustody.ExportableSoftware

    override fun eligible(alg: ProtectedKeyAlgorithm): Boolean = true

    override suspend fun generateSigning(
        scheme: SignatureScheme,
        spec: ProtectedKeySpec,
    ): SigningKey = SoftwareKeyProvider.generateSigning(scheme, spec)

    override suspend fun generateAesGcm(spec: ProtectedKeySpec): AesGcmKey = SoftwareKeyProvider.generateAesGcm(spec)

    override suspend fun generateKeyAgreement(
        curve: KeyAgreementCurve,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair = SoftwareKeyProvider.generateKeyAgreement(curve, spec)

    // --- Persistent alias lifecycle -------------------------------------------------

    override suspend fun getOrGenerateSigning(
        alias: String,
        scheme: SignatureScheme,
        spec: ProtectedKeySpec,
    ): SigningKey {
        requireValidAlias(alias)
        storage.get(alias)?.let { blob ->
            val decoded = decodeBlob(blob)
            if (decoded.kind != KIND_SIGNING || signingScheme(decoded.tag) != scheme) {
                throw KeyStoreException.AliasMismatch(
                    alias = alias,
                    stored = storedAlgorithm(decoded.kind, decoded.tag),
                    requested = scheme.toProtectedKeyAlgorithm(),
                )
            }
            return signingKeyOf(scheme, decoded.privateEncoded, verifyKeyOf(scheme, decoded.publicEncoded), secureFactory)
        }
        val key = SoftwareKeyProvider.generateSigning(scheme, spec)
        persist(alias, encodeBlob(KIND_SIGNING, signingTag(scheme), key.requireInMemoryMaterial(), key.verifyKey.exportEncoded()))
        return key
    }

    override suspend fun getOrGenerateAesGcm(
        alias: String,
        spec: ProtectedKeySpec,
    ): AesGcmKey {
        requireValidAlias(alias)
        storage.get(alias)?.let { blob ->
            val decoded = decodeBlob(blob)
            if (decoded.kind != KIND_AES_GCM) {
                throw KeyStoreException.AliasMismatch(
                    alias = alias,
                    stored = storedAlgorithm(decoded.kind, decoded.tag),
                    requested = ProtectedKeyAlgorithm.AesGcm,
                )
            }
            return AesGcmKey.of(decoded.privateEncoded)
        }
        val keyBytes = cryptoRandom(spec.aesKeySizeBits / Byte.SIZE_BITS, secureFactory)
        persist(alias, encodeBlob(KIND_AES_GCM, 0, keyBytes, emptyBuffer))
        val key = AesGcmKey.of(keyBytes)
        keyBytes.freeNativeMemory()
        return key
    }

    override suspend fun getOrGenerateKeyAgreement(
        alias: String,
        curve: KeyAgreementCurve,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair {
        requireValidAlias(alias)
        storage.get(alias)?.let { blob ->
            val decoded = decodeBlob(blob)
            if (decoded.kind != KIND_AGREEMENT || agreementCurve(decoded.tag) != curve) {
                throw KeyStoreException.AliasMismatch(
                    alias = alias,
                    stored = storedAlgorithm(decoded.kind, decoded.tag),
                    requested = curve.toProtectedKeyAlgorithm(),
                )
            }
            return keyAgreementKeyPairOf(
                curve,
                importPrivateKey(curve, decoded.privateEncoded),
                KeyAgreementPublicKey.of(curve, decoded.publicEncoded),
            )
        }
        val pair = SoftwareKeyProvider.generateKeyAgreement(curve, spec)
        val privateEncoded = pair.privateKey.exportEncoded(secureFactory)
        persist(alias, encodeBlob(KIND_AGREEMENT, agreementTag(curve), privateEncoded, pair.publicKey.encoded))
        return pair
    }

    override suspend fun loadSigning(alias: String): SigningKey? {
        requireValidAlias(alias)
        val decoded = storage.get(alias)?.let { decodeBlob(it) } ?: return null
        if (decoded.kind != KIND_SIGNING) return null
        val scheme = signingScheme(decoded.tag)
        return signingKeyOf(scheme, decoded.privateEncoded, verifyKeyOf(scheme, decoded.publicEncoded), secureFactory)
    }

    override suspend fun loadAesGcm(alias: String): AesGcmKey? {
        requireValidAlias(alias)
        val decoded = storage.get(alias)?.let { decodeBlob(it) } ?: return null
        if (decoded.kind != KIND_AES_GCM) return null
        return AesGcmKey.of(decoded.privateEncoded)
    }

    override suspend fun loadKeyAgreement(alias: String): KeyAgreementKeyPair? {
        requireValidAlias(alias)
        val decoded = storage.get(alias)?.let { decodeBlob(it) } ?: return null
        if (decoded.kind != KIND_AGREEMENT) return null
        val curve = agreementCurve(decoded.tag)
        return keyAgreementKeyPairOf(
            curve,
            importPrivateKey(curve, decoded.privateEncoded),
            KeyAgreementPublicKey.of(curve, decoded.publicEncoded),
        )
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

    /** Persist [blob] under [alias], then wipe the transient secret-bearing blob copy. */
    private suspend fun persist(
        alias: String,
        blob: PlatformBuffer,
    ) {
        try {
            storage.put(alias, blob)
        } finally {
            blob.freeNativeMemory()
        }
    }

    private companion object {
        val secureFactory: BufferFactory = BufferFactory.deterministicSecure()
        val emptyBuffer: ReadBuffer = BufferFactory.Default.allocate(0).also { it.resetForRead() }
    }
}
