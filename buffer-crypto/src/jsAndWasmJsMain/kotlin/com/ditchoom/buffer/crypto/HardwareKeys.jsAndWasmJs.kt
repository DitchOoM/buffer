package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.use

/*
 * WebCrypto non-exportable **software** key provider (js/wasmJs).
 *
 * WebCrypto `subtle.generateKey(..., extractable = false, ...)` mints keys whose private material
 * never enters the JS heap: the browser/engine holds the `CryptoKey`, and every operation drives it
 * through `subtle.sign` / `subtle.encrypt` / `subtle.deriveBits`. That is exactly this SPI's
 * [KeyCustody.NonExportable.Software] tier — non-exportable, but *not* a hardware secure element — so
 * [platformProtectedKeyProvider] returns a provider here (and [CryptoCapabilities.protectedKeys]
 * reports [ProtectedKeySupport.Available]), while [CryptoCapabilities.hardware] stays
 * [HardwareSupport.Unavailable] (this is not a [HardwareKeyProvider]).
 *
 * WebCrypto is Promise-based, which maps directly onto the `suspend` gated closures each key carries;
 * a non-exportable key is async-only anyway (it is not a `SyncCapable*` type), so there is no blocking
 * path to miss. The keys the engine holds are referenced by an integer token into a per-target JS-side
 * registry (see `HardwareKeys.js.kt` / `HardwareKeys.wasmJs.kt`); the token — never the key bytes —
 * crosses the Kotlin↔JS boundary, matching the hex-string marshalling the rest of the web bridge uses.
 * Each key's `onClose` drops its registry entry so the `CryptoKey` can be collected.
 *
 * Eligibility mirrors what WebCrypto reliably backs: ECDSA P-256/384/521 (P-256 primary), AES-GCM,
 * and ECDH P-256/384/521. X25519 is offered only where the engine exposes it (feature-detected via
 * [webCryptoSupportsX25519]); otherwise the resolver routes X25519 to the software floor. Ed25519 is
 * excluded (uneven browser support), mirroring the Android secure-element subset.
 *
 * ZEROIZATION NOTE: the non-exportable private material is precisely what never reaches Kotlin, so
 * there is nothing to wipe on the private side. Public keys, nonces, ciphertext, and derived material
 * still transit the boundary as hex Strings (the documented, accepted web limitation shared with the
 * rest of this module).
 */
internal object WebCryptoProtectedKeyProvider : ProtectedKeyProvider {
    override val custody: KeyCustody.NonExportable get() = KeyCustody.NonExportable.Software

    override fun eligible(alg: ProtectedKeyAlgorithm): Boolean =
        when (alg) {
            ProtectedKeyAlgorithm.AesGcm,
            ProtectedKeyAlgorithm.EcdsaP256,
            ProtectedKeyAlgorithm.EcdsaP384,
            ProtectedKeyAlgorithm.EcdsaP521,
            ProtectedKeyAlgorithm.EcdhP256,
            ProtectedKeyAlgorithm.EcdhP384,
            ProtectedKeyAlgorithm.EcdhP521,
            -> true
            // X25519 only where the engine implements it; otherwise the resolver falls to software.
            ProtectedKeyAlgorithm.X25519 -> webCryptoSupportsX25519
            // Ed25519 support is uneven across engines; excluded from the non-exportable tier for now.
            ProtectedKeyAlgorithm.Ed25519 -> false
        }

    override suspend fun generateSigning(
        scheme: SignatureScheme,
        spec: ProtectedKeySpec,
    ): SigningKey {
        if (!eligible(scheme.toProtectedKeyAlgorithm())) throw HardwareKeyException.AlgorithmNotEligible()
        val (token, publicHex) = splitTokenAndHex(webCryptoGenerateNonExportableEcdsa(ecdsaCurveName(scheme)))
        return buildWebCryptoSigningKey(scheme, token, publicHex, spec)
    }

    override suspend fun generateAesGcm(spec: ProtectedKeySpec): AesGcmKey {
        val sizeBits = spec.aesKeySizeBits
        if (sizeBits != AES_128_KEY_BYTES * Byte.SIZE_BITS && sizeBits != AES_256_KEY_BYTES * Byte.SIZE_BITS) {
            throw HardwareKeyException.UnsupportedHardwareKey()
        }
        val token = webCryptoGenerateNonExportableAesGcm(sizeBits).toInt()
        return buildWebCryptoAesGcmKey(sizeBits, token, spec)
    }

    override suspend fun generateKeyAgreement(
        curve: KeyAgreementCurve,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair {
        if (!eligible(curve.toProtectedKeyAlgorithm())) throw HardwareKeyException.AlgorithmNotEligible()
        val result = webCryptoGenerateNonExportableEcdh(curve.curveName)
        if (result == UNSUPPORTED_SENTINEL) throw HardwareKeyException.AlgorithmNotEligible()
        val (token, publicHex) = splitTokenAndHex(result)
        return buildWebCryptoKeyAgreementPair(curve, token, publicHex, spec)
    }
}

// =============================================================================
// Non-exportable key builders — shared by the provider (fresh keys) and the WebCrypto KeyStore
// (keys re-registered from IndexedDB on load). A key is fully described by its (token, publicHex),
// so a reloaded key is byte-for-byte the same handle as a freshly generated one.
// =============================================================================

/** Wraps a registered non-exportable ECDSA private-key [token] (+ its [publicHex]) as a [SigningKey]. */
internal fun buildWebCryptoSigningKey(
    scheme: SignatureScheme,
    token: Int,
    publicHex: String,
    spec: ProtectedKeySpec,
): SigningKey {
    val hashName = ecdsaHashName(scheme)
    val verifyKey = verifyKeyOf(scheme, hexBuffer(publicHex, BufferFactory.Default))
    return ProtectedSigningKey(
        scheme = scheme,
        custody = KeyCustody.NonExportable.Software,
        gatedSign = { message, factory ->
            if (!spec.authorization.authorize()) throw AuthorizationFailed()
            // WebCrypto ECDSA sign is raw P1363 (r ‖ s) — the pinned web wire form; DER conversion
            // is a consumer's concern via the existing helpers.
            hexBuffer(webCryptoEcdsaSign(token, hashName, message.toHexRemaining()), factory)
        },
        verifyKey = verifyKey,
        onClose = { webCryptoReleaseHandle(token) },
    )
}

/** Wraps a registered non-exportable AES-GCM key [token] as an [AesGcmKey]. */
internal fun buildWebCryptoAesGcmKey(
    sizeBits: Int,
    token: Int,
    spec: ProtectedKeySpec,
): AesGcmKey =
    ProtectedAesGcmKey(
        sizeBits = sizeBits,
        custody = KeyCustody.NonExportable.Software,
        // WebCrypto requires the caller to supply the IV, so — unlike the Android keystore path —
        // the seal closure mints a fresh 12-byte nonce here and frames nonce ‖ ciphertext ‖ tag so
        // the standard splitFramed open path is unchanged. A reused (key, nonce) pair is
        // catastrophic, so the nonce is drawn from the library CSPRNG per seal, never from a caller.
        gatedSeal = { aad, plaintext, factory ->
            if (!spec.authorization.authorize()) throw AuthorizationFailed()
            cryptoRandom(AEAD_NONCE_BYTES).use { nonce ->
                val ctTagHex =
                    webCryptoAesGcmSealHandle(
                        token = token,
                        ivHex = nonce.toHexRemaining(),
                        aadHex = aad?.toHexRemaining() ?: "",
                        plaintextHex = plaintext.toHexRemaining(),
                    )
                val out = allocateFramed(plaintext.remaining(), factory)
                out.write(nonce)
                out.writeHex(ctTagHex)
                out.resetForRead()
                out
            }
        },
        gatedOpen = { nonce, aad, ciphertextAndTag, factory ->
            if (!spec.authorization.authorize()) throw AuthorizationFailed()
            val ptHex =
                webCryptoAesGcmOpenHandle(
                    token = token,
                    ivHex = nonce.toHexRemaining(),
                    aadHex = aad?.toHexRemaining() ?: "",
                    ciphertextAndTagHex = ciphertextAndTag.toHexRemaining(),
                ) ?: throw VerificationFailed()
            hexBuffer(ptHex, factory)
        },
        onClose = { webCryptoReleaseHandle(token) },
    )

/** Wraps a registered non-exportable ECDH/X25519 private-key [token] (+ its [publicHex]) as a key pair. */
internal fun buildWebCryptoKeyAgreementPair(
    curve: KeyAgreementCurve,
    token: Int,
    publicHex: String,
    spec: ProtectedKeySpec,
): KeyAgreementKeyPair {
    val publicKey = KeyAgreementPublicKey.of(curve, hexBuffer(publicHex, BufferFactory.Default))
    val bits = deriveBitsFor(curve)
    val privateKey =
        ProtectedKeyAgreementPrivateKey(
            curve = curve,
            custody = KeyCustody.NonExportable.Software,
            // The gated DH is exactly the KEM's DH(sk, peer): deriveBits over the non-exportable
            // private handle. The common seam ([dhRawSecret] / [deriveFromRawSecret]) applies the
            // shared all-zero rejection + HKDF above it, so hpke().openBase(...) and
            // keyAgreement().deriveSharedSecret(...) compose unchanged with this recipient key.
            gatedDh = { peer ->
                if (!spec.authorization.authorize()) throw AuthorizationFailed()
                require(peer.curve == curve) { "private/public key curve mismatch" }
                val sharedHex =
                    try {
                        webCryptoEcdhDeriveBits(token, curve.curveName, peer.encoded.toHexRemaining(), bits)
                    } catch (_: Throwable) {
                        // A rejected import / deriveBits means an off-curve / low-order / malformed
                        // peer point — surfaced uniformly, cause dropped (oracle avoidance).
                        @Suppress("SwallowedException", "TooGenericExceptionCaught")
                        throw InvalidPublicKey(curve)
                    }
                hexBuffer(sharedHex, secureScratch)
            },
            onClose = { webCryptoReleaseHandle(token) },
        )
    return keyAgreementKeyPairOf(curve, privateKey, publicKey)
}

/**
 * Browser/Node and WASM expose non-exportable WebCrypto software keys but no dedicated secure element,
 * so [platformProtectedKeyProvider] returns the [WebCryptoProtectedKeyProvider] (a
 * [ProtectedKeyProvider], **not** a [HardwareKeyProvider]) only when `subtle.generateKey` is present;
 * [CryptoCapabilities.hardware] stays [HardwareSupport.Unavailable].
 */
internal actual fun platformProtectedKeyProvider(): ProtectedKeyProvider? =
    if (subtleGenerateKeyAvailable) WebCryptoProtectedKeyProvider else null

// =============================================================================
// helpers (shared js/wasmJs)
// =============================================================================

/** ECDSA WebCrypto `namedCurve` for [scheme]. */
private fun ecdsaCurveName(scheme: SignatureScheme): String =
    when (scheme) {
        SignatureScheme.EcdsaP256 -> "P-256"
        SignatureScheme.EcdsaP384 -> "P-384"
        SignatureScheme.EcdsaP521 -> "P-521"
        SignatureScheme.Ed25519 -> throw HardwareKeyException.AlgorithmNotEligible()
    }

/** ECDSA WebCrypto `hash` name for [scheme] (each curve is bound to its FIPS 186 hash). */
private fun ecdsaHashName(scheme: SignatureScheme): String =
    when (scheme) {
        SignatureScheme.EcdsaP256 -> "SHA-256"
        SignatureScheme.EcdsaP384 -> "SHA-384"
        SignatureScheme.EcdsaP521 -> "SHA-512"
        SignatureScheme.Ed25519 -> throw HardwareKeyException.AlgorithmNotEligible()
    }

/** WebCrypto `deriveBits` length for [curve] (P-521 rounds up to 528 bits, matching the KA glue). */
private fun deriveBitsFor(curve: KeyAgreementCurve): Int =
    when (curve) {
        KeyAgreementCurve.X25519, KeyAgreementCurve.P256 -> 256
        KeyAgreementCurve.P384 -> 384
        KeyAgreementCurve.P521 -> 528
    }

/** Splits a `"<token>:<hex>"` marshalling into the integer token and the hex payload. */
private fun splitTokenAndHex(marshalled: String): Pair<Int, String> {
    val sep = marshalled.indexOf(':')
    require(sep > 0) { "malformed non-exportable key marshalling" }
    return marshalled.substring(0, sep).toInt() to marshalled.substring(sep + 1)
}

/** Builds a fresh read-ready buffer from a lowercase-hex string via [factory]. */
private fun hexBuffer(
    hex: String,
    factory: BufferFactory,
): PlatformBuffer {
    val out = factory.allocate(hex.length / 2)
    out.writeHex(hex)
    out.resetForRead()
    return out
}

// =============================================================================
// Per-target WebCrypto seam (dynamic on JS, @JsFun externals on wasmJs)
// =============================================================================
//
// Non-exportable CryptoKeys live in a per-target JS-side registry keyed by an integer token; only the
// token (and hex-marshalled public/derived material) crosses the boundary, so the same jsAndWasmJs
// logic drives both backends without typed-array interop leaking in.

/** Whether `crypto.subtle.generateKey` is callable in this engine (gates the provider). */
internal expect val subtleGenerateKeyAvailable: Boolean

/**
 * Generates a non-extractable ECDSA key pair for [curveName], stores the private `CryptoKey` in the
 * registry, and returns `"<token>:<rawPublicHex>"` (the uncompressed SEC1 point).
 */
internal expect suspend fun webCryptoGenerateNonExportableEcdsa(curveName: String): String

/** Signs [messageHex] with the registered ECDSA private key [token] under [hashName]; returns P1363 hex. */
internal expect suspend fun webCryptoEcdsaSign(
    token: Int,
    hashName: String,
    messageHex: String,
): String

/** Generates a non-extractable AES-GCM key of [lengthBits] bits, stores it, and returns the token as a string. */
internal expect suspend fun webCryptoGenerateNonExportableAesGcm(lengthBits: Int): String

/** AES-GCM encrypt with the registered key [token]; returns `ciphertext ‖ tag` hex (WebCrypto appends the tag). */
internal expect suspend fun webCryptoAesGcmSealHandle(
    token: Int,
    ivHex: String,
    aadHex: String,
    plaintextHex: String,
): String

/** AES-GCM decrypt with the registered key [token]; returns plaintext hex, or `null` on a bad tag. */
internal expect suspend fun webCryptoAesGcmOpenHandle(
    token: Int,
    ivHex: String,
    aadHex: String,
    ciphertextAndTagHex: String,
): String?

/**
 * Generates a non-extractable ECDH/X25519 key pair for [curveName], stores the private key, and
 * returns `"<token>:<rawPublicHex>"` — or [UNSUPPORTED_SENTINEL] if the engine lacks the algorithm.
 */
internal expect suspend fun webCryptoGenerateNonExportableEcdh(curveName: String): String

/**
 * Computes the raw ECDH/X25519 shared secret between the registered private key [token] and
 * [peerPublicHex] (raw peer point), returning [bits]-worth of raw secret as hex. Rejects an
 * off-curve / low-order peer point by rejecting the underlying Promise (mapped to [InvalidPublicKey]).
 */
internal expect suspend fun webCryptoEcdhDeriveBits(
    token: Int,
    curveName: String,
    peerPublicHex: String,
    bits: Int,
): String

/** Drops the registry entry for [token] so its non-exportable `CryptoKey` can be collected. */
internal expect fun webCryptoReleaseHandle(token: Int)

// =============================================================================
// IndexedDB persistence seam — durable non-extractable CryptoKeys keyed by alias
// =============================================================================
//
// A non-extractable CryptoKey cannot be turned into bytes (that is the guarantee), so it is stored in
// IndexedDB *as an object* (structured clone preserves the key AND its extractable:false flag). Each
// record is `{ meta: "<kind>:<tag>:<pubHex>", key: CryptoKey }` under the alias. On load the key is
// re-registered into the in-memory token registry, so a reloaded key is the same handle shape as a
// fresh one. Only strings / ints cross the boundary — the CryptoKey never enters Kotlin.

/** Whether `globalThis.indexedDB` is present (gates the WebCrypto key store; false under bare Node). */
internal expect val webCryptoIndexedDbAvailable: Boolean

/** Stores the registered CryptoKey for [token] plus [meta] in database [dbName] under [alias]. */
internal expect suspend fun webCryptoIdbPut(
    dbName: String,
    alias: String,
    token: Int,
    meta: String,
)

/**
 * Loads the record under [alias], re-registers its CryptoKey into the token registry, and returns
 * `"<token>:<meta>"` (i.e. `"<token>:<kind>:<tag>:<pubHex>"`), or `null` if [alias] is absent.
 */
internal expect suspend fun webCryptoIdbLoad(
    dbName: String,
    alias: String,
): String?

/** Deletes [alias] from [dbName]; `true` if a record existed. */
internal expect suspend fun webCryptoIdbDelete(
    dbName: String,
    alias: String,
): Boolean

/** Whether [alias] exists in [dbName]. */
internal expect suspend fun webCryptoIdbContains(
    dbName: String,
    alias: String,
): Boolean

/** Every alias in [dbName], newline-joined (aliases are validated to a charset without `\n`); empty if none. */
internal expect suspend fun webCryptoIdbAliases(dbName: String): String

/**
 * A persistent [KeyStore] whose keys are non-extractable WebCrypto `CryptoKey`s durably held in
 * IndexedDB ([KeyCustody.NonExportable.Software]). The private material never enters the JS heap on
 * generation *or* reload — it is stored and restored as an opaque `CryptoKey`. The ephemeral
 * [KeyProvider] methods delegate to [WebCryptoProtectedKeyProvider]; the alias methods add durability.
 *
 * Serves only the algorithms WebCrypto backs non-exportably (ECDSA/ECDH P-256/384/521, AES-GCM,
 * X25519 where present); [getOrGenerateSigning] for an ineligible scheme (Ed25519) throws
 * [HardwareKeyException.AlgorithmNotEligible] — consult [custodyFor] / [eligible] first. AES key size
 * is carried in the record's tag slot so it reloads without a separate field.
 */
internal class WebCryptoKeyStore(
    private val dbName: String,
) : KeyStore {
    override fun custodyFor(alg: ProtectedKeyAlgorithm): KeyCustody = KeyCustody.NonExportable.Software

    override fun eligible(alg: ProtectedKeyAlgorithm): Boolean = WebCryptoProtectedKeyProvider.eligible(alg)

    override suspend fun generateSigning(
        scheme: SignatureScheme,
        spec: ProtectedKeySpec,
    ): SigningKey = WebCryptoProtectedKeyProvider.generateSigning(scheme, spec)

    override suspend fun generateAesGcm(spec: ProtectedKeySpec): AesGcmKey = WebCryptoProtectedKeyProvider.generateAesGcm(spec)

    override suspend fun generateKeyAgreement(
        curve: KeyAgreementCurve,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair = WebCryptoProtectedKeyProvider.generateKeyAgreement(curve, spec)

    override suspend fun getOrGenerateSigning(
        alias: String,
        scheme: SignatureScheme,
        spec: ProtectedKeySpec,
    ): SigningKey {
        requireValidAlias(alias)
        loadEntry(alias)?.let { e ->
            if (e.kind != KIND_SIGNING || signingScheme(e.tag) != scheme) {
                webCryptoReleaseHandle(e.token)
                throw KeyStoreException.AliasMismatch(alias, storedAlgorithm(e.kind, e.tag), scheme.toProtectedKeyAlgorithm())
            }
            return buildWebCryptoSigningKey(scheme, e.token, e.pubHex, spec)
        }
        if (!eligible(scheme.toProtectedKeyAlgorithm())) throw HardwareKeyException.AlgorithmNotEligible()
        val (token, pubHex) = splitTokenAndHex(webCryptoGenerateNonExportableEcdsa(ecdsaCurveName(scheme)))
        webCryptoIdbPut(dbName, alias, token, "$KIND_SIGNING:${signingTag(scheme)}:$pubHex")
        return buildWebCryptoSigningKey(scheme, token, pubHex, spec)
    }

    override suspend fun getOrGenerateAesGcm(
        alias: String,
        spec: ProtectedKeySpec,
    ): AesGcmKey {
        requireValidAlias(alias)
        loadEntry(alias)?.let { e ->
            if (e.kind != KIND_AES_GCM) {
                webCryptoReleaseHandle(e.token)
                throw KeyStoreException.AliasMismatch(alias, storedAlgorithm(e.kind, e.tag), ProtectedKeyAlgorithm.AesGcm)
            }
            return buildWebCryptoAesGcmKey(e.tag, e.token, spec) // AES size is carried in the tag slot
        }
        val sizeBits = spec.aesKeySizeBits
        val token = webCryptoGenerateNonExportableAesGcm(sizeBits).toInt()
        webCryptoIdbPut(dbName, alias, token, "$KIND_AES_GCM:$sizeBits:")
        return buildWebCryptoAesGcmKey(sizeBits, token, spec)
    }

    override suspend fun getOrGenerateKeyAgreement(
        alias: String,
        curve: KeyAgreementCurve,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair {
        requireValidAlias(alias)
        loadEntry(alias)?.let { e ->
            if (e.kind != KIND_AGREEMENT || agreementCurve(e.tag) != curve) {
                webCryptoReleaseHandle(e.token)
                throw KeyStoreException.AliasMismatch(alias, storedAlgorithm(e.kind, e.tag), curve.toProtectedKeyAlgorithm())
            }
            return buildWebCryptoKeyAgreementPair(curve, e.token, e.pubHex, spec)
        }
        if (!eligible(curve.toProtectedKeyAlgorithm())) throw HardwareKeyException.AlgorithmNotEligible()
        val result = webCryptoGenerateNonExportableEcdh(curve.curveName)
        if (result == UNSUPPORTED_SENTINEL) throw HardwareKeyException.AlgorithmNotEligible()
        val (token, pubHex) = splitTokenAndHex(result)
        webCryptoIdbPut(dbName, alias, token, "$KIND_AGREEMENT:${agreementTag(curve)}:$pubHex")
        return buildWebCryptoKeyAgreementPair(curve, token, pubHex, spec)
    }

    override suspend fun loadSigning(alias: String): SigningKey? {
        requireValidAlias(alias)
        val e = loadEntry(alias) ?: return null
        if (e.kind != KIND_SIGNING) {
            webCryptoReleaseHandle(e.token)
            return null
        }
        return buildWebCryptoSigningKey(signingScheme(e.tag), e.token, e.pubHex, ProtectedKeySpec())
    }

    override suspend fun loadAesGcm(alias: String): AesGcmKey? {
        requireValidAlias(alias)
        val e = loadEntry(alias) ?: return null
        if (e.kind != KIND_AES_GCM) {
            webCryptoReleaseHandle(e.token)
            return null
        }
        return buildWebCryptoAesGcmKey(e.tag, e.token, ProtectedKeySpec())
    }

    override suspend fun loadKeyAgreement(alias: String): KeyAgreementKeyPair? {
        requireValidAlias(alias)
        val e = loadEntry(alias) ?: return null
        if (e.kind != KIND_AGREEMENT) {
            webCryptoReleaseHandle(e.token)
            return null
        }
        return buildWebCryptoKeyAgreementPair(agreementCurve(e.tag), e.token, e.pubHex, ProtectedKeySpec())
    }

    override suspend fun contains(alias: String): Boolean {
        requireValidAlias(alias)
        return webCryptoIdbContains(dbName, alias)
    }

    override suspend fun aliases(): Set<String> = webCryptoIdbAliases(dbName).split('\n').filterTo(mutableSetOf()) { it.isNotEmpty() }

    override suspend fun delete(alias: String): Boolean {
        requireValidAlias(alias)
        return webCryptoIdbDelete(dbName, alias)
    }

    override fun close() = Unit

    /** A decoded IDB record: the re-registered token plus its `"<kind>:<tag>:<pubHex>"` metadata. */
    private class Entry(
        val token: Int,
        val kind: Int,
        val tag: Int,
        val pubHex: String,
    )

    private suspend fun loadEntry(alias: String): Entry? {
        val marshalled = webCryptoIdbLoad(dbName, alias) ?: return null
        // "<token>:<kind>:<tag>:<pubHex>" — none of token/kind/tag/pubHex contain ':'.
        val parts = marshalled.split(':', limit = 4)
        return Entry(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), parts.getOrElse(3) { "" })
    }
}
