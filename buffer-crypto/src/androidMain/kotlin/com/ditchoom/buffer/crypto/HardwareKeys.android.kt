@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file

package com.ditchoom.buffer.crypto

import android.os.Build
import android.security.KeyStoreException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import androidx.biometric.BiometricPrompt
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import kotlin.time.TimeSource

/*
 * Android Keystore hardware-backed key provider.
 *
 * Keys are *generated inside* the AndroidKeyStore (`setIsStrongBoxBacked(true)` when a StrongBox
 * secure element is present, falling back to the TEE otherwise) and never leave it: the gated
 * closures drive a JCA `Cipher` / `Signature` over the keystore key handle, so the secret bytes are
 * never in process memory. Only the keystore-generated 12-byte GCM nonce and the JDK-required DER
 * signature bytes cross the JCA boundary as arrays — the payload itself streams buffer→cipher→buffer
 * through the shared [finalInto] / [openInto] plumbing, identical to the in-memory AES-GCM path.
 *
 * Eligibility mirrors what a secure element actually backs: AES-256/128-GCM, ECDSA P-256, and — on
 * API 31+ where the keystore honors `PURPOSE_AGREE_KEY` (probed, not inferred from the API level) —
 * ECDH P-256 key agreement. (Ed25519 / P-384 / P-521 / X25519 are not offered — see [eligible].)
 *
 * **Robustness shape (all driven by real device behaviour):**
 *  - **A refused StrongBox generation falls back to the TEE, with the reason on the key.** A device
 *    that lacks (or under-provisions) a secure element does not reliably throw
 *    [StrongBoxUnavailableException]: it may refuse with a keystore code instead (see
 *    [strongBoxRefusal]). A *transient* refusal (StrongBox busy) is asked of StrongBox once more
 *    ([STRONGBOX_ATTEMPTS] attempts in all), so a moment's contention does not settle a key in the TEE
 *    for life; after that, and for every other refusal, the key is made in the TEE and carries
 *    [SecureElementFallback.ToWeakerSecureTier] naming why. Only a failure of the TEE generation itself throws.
 *  - **Every key reports its own custody.** After each generation and reattach the provider reads
 *    the key's `KeyInfo` security level and accepts only TEE or StrongBox: a key the keystore placed
 *    anywhere else (a software KeyMint behind a compatibility layer, an unknown level) is deleted
 *    and refused with [InsufficientKeyCustody]. The custody on each key is what its `KeyInfo` says,
 *    never what was requested.
 *  - **The keystore generates the GCM IV.** AES keys are *randomized-encryption-required* (the JCA
 *    default), so `Cipher.init(ENCRYPT_MODE, key)` makes the keystore mint a fresh IV; the seal reads
 *    it back via [Cipher.getIV] and frames `nonce ‖ ciphertext ‖ tag`. The library never supplies a
 *    seal IV — a single reused GCM nonce is catastrophic, and letting the element choose matches
 *    Signal / Tink / AndroidX.
 *  - **The provider's own custody is the floor.** Which element holds a key is known only per key,
 *    so [dedicatedSecureElement] is `false` and [custody] is the TEE floor every key is guaranteed;
 *    a StrongBox key says so on its own [KeyCustody].
 *  - **All keystore access is serialized + off the calling thread.** AndroidKeyStore is not
 *    thread-safe and StrongBox ops are slow, so generation and every per-op cipher/signature run
 *    under a [Mutex] on [Dispatchers.Default]. A persistent get-or-generate looks the alias up and
 *    generates in one such section ([getOrGenerateEntry]), so two callers in this process cannot both
 *    generate under one alias. The auth gate ([HardwareAuthorization.authorize]) is evaluated
 *    *outside* the lock — a biometric prompt must not hold the keystore mutex.
 *
 * **User authentication** is a [ResolvedAndroidPolicy], fixed at generation:
 *  - [ResolvedAndroidPolicy.Advisory] — the base provider's keys: no OS binding, the
 *    [HardwareAuthorization] gate is evaluated before every op (the pre-biometrics behavior).
 *  - [ResolvedAndroidPolicy.Session] — via [userAuthenticated]: the key is generated with
 *    `setUserAuthenticationRequired(true)` + a validity window (`setUserAuthenticationParameters`
 *    on API 30+, the deprecated validity-duration seconds on 28/29), so the *keystore* refuses a
 *    stale op with `UserNotAuthenticatedException`. The provider then prompts through the
 *    captured [BiometricAuthorization] and retries exactly once — an already-authenticated user
 *    is never re-prompted.
 *  - [ResolvedAndroidPolicy.PerUse] — via [userAuthenticated]: auth-per-use binding (timeout 0 /
 *    validity -1), which Android only honors through a `BiometricPrompt.CryptoObject` wrapping
 *    the exact `Cipher`/`Signature` — which is exactly why [userAuthenticated] demands a
 *    [BiometricAuthorization] at the type level (the library ships [BiometricPromptAuthenticator]).
 */
internal class AndroidKeystoreHardwareKeyProvider(
    private val keyStore: KeyStore,
    /** Where keystore work runs, off the caller's thread. */
    private val keystoreDispatcher: CoroutineDispatcher,
) : HardwareKeyProvider {
    private val gcmTagBits = AEAD_TAG_BYTES * Byte.SIZE_BITS

    /**
     * Serializes suspend keystore ops (so only one [Dispatchers.Default] worker is ever blocked on a
     * slow StrongBox round-trip at a time). [keystoreMonitor] additionally excludes the non-suspend
     * [probeAgreement] — which can't take the suspend [Mutex] — so the probe and live ops never
     * touch the keystore concurrently. Lock order is always Mutex → monitor; the probe takes only the
     * monitor, so there is no cycle.
     */
    private val keystoreLock = Mutex()
    private val keystoreMonitor = Any()

    /**
     * `false`: the floor every key from this provider is guaranteed (the TEE). Whether StrongBox holds
     * a particular key is known only from that key's `KeyInfo`, so it is reported on the key's own
     * [KeyCustody], not pinned here for every key.
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override val dedicatedSecureElement: Boolean get() = false

    /**
     * `true` once a `PURPOSE_AGREE_KEY` generation lands in secure hardware *and* a full keystore
     * ECDH against a software peer succeeds. Probed (generate-and-agree with a throwaway key) rather
     * than inferred from the API level: `PURPOSE_AGREE_KEY` exists from API 31, but whether the
     * device's KeyMint actually implements agreement in hardware — rather than in a software KeyMint
     * behind a compatibility layer — is exactly what devices diverge on. A transient keystore refusal
     * is not settled, so the next eligibility check probes again.
     */
    private val agreementSupport = SettlingLazy(PROBE_RETRY_AFTER, TimeSource.Monotonic) { probeAgreement() }

    private val agreementSupported: Boolean get() = agreementSupport.get()

    override fun eligible(alg: ProtectedKeyAlgorithm): Boolean =
        when (alg) {
            ProtectedKeyAlgorithm.AesGcm, ProtectedKeyAlgorithm.EcdsaP256 -> true
            ProtectedKeyAlgorithm.EcdhP256 -> agreementSupported
            else -> false
        }

    override suspend fun generateAesGcm(spec: ProtectedKeySpec): AesGcmKey =
        generateAesGcmBound(ResolvedAndroidPolicy.Advisory(spec.authorization), spec.aesKeySizeBits)

    override suspend fun generateSigning(
        scheme: SignatureScheme,
        spec: ProtectedKeySpec,
    ): SigningKey = generateSigningBound(ResolvedAndroidPolicy.Advisory(spec.authorization), scheme)

    override suspend fun generateKeyAgreement(
        curve: KeyAgreementCurve,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair {
        // Only ECDH P-256, and only where the keystore-probed PURPOSE_AGREE_KEY support holds
        // (API 31+). X25519 has no AndroidKeyStore agreement backing on any API level.
        if (curve != KeyAgreementCurve.P256 || !eligible(ProtectedKeyAlgorithm.EcdhP256)) {
            throw HardwareKeyException.AlgorithmNotEligible()
        }
        val policy = ResolvedAndroidPolicy.Advisory(spec.authorization)
        val alias = newAlias("ecdh")
        val placed = keystoreOp { generateAgreementKeyPair(alias, policy) }
        val publicKey = KeyAgreementPublicKey.of(curve, uncompressedPoint(placed.key.public as ECPublicKey))
        // Ephemeral: the scratch alias is deleted when the returned pair (or its private key) is closed.
        return buildKeyAgreementPair(
            placed.key.private,
            publicKey,
            policy,
            placed.placement,
            onClose = { runCatching { keyStore.deleteEntry(alias) } },
        )
    }

    /**
     * Wraps an AndroidKeystore ECDH private-key handle as a [KeyAgreementKeyPair] gated by
     * [policy]. The [ResolvedAndroidPolicy.Session] arm is the only OS-bound one reachable for key
     * agreement: `BiometricPrompt.CryptoObject` has no `KeyAgreement` overload, so a per-derive
     * (`PerUse`) agreement key cannot be bound on Android — the type system already prevents it (the
     * `UserAuthenticatedKeyProvider.generateKeyAgreement` surface takes only
     * [UserAuthenticationPolicy.Windowed], and this provider is not a [PerUseAgreementCapable]), and
     * [gatedAgreementDh] rejects a stray `PerUse` defensively.
     */
    internal fun buildKeyAgreementPair(
        privateKey: PrivateKey,
        publicKey: KeyAgreementPublicKey,
        policy: ResolvedAndroidPolicy,
        placement: HardwarePlacement,
        onClose: () -> Unit,
    ): KeyAgreementKeyPair {
        val curve = publicKey.curve
        val agreementPrivateKey =
            ProtectedKeyAgreementPrivateKey(
                curve = curve,
                custody = placement.custody,
                secureElementFallback = placement.fallback,
                gatedDh = { peer ->
                    require(peer.curve == curve) { "private/public key curve mismatch" }
                    // Peer-point conversion happens outside the keystore lock; a malformed or
                    // off-curve point is rejected before the element is ever touched.
                    val jcaPeer = p256PublicKey(peer.encoded)
                    gatedAgreementDh(policy) { keystoreOp { agreeP256(privateKey, jcaPeer) } }
                },
                onClose = onClose,
            )
        return keyAgreementKeyPairOf(curve, agreementPrivateKey, publicKey)
    }

    /**
     * Runs one keystore ECDH [dh] under the key's [policy] — the agreement analogue of [gatedOp],
     * minus the `CryptoObject` path (a `KeyAgreement` cannot be wrapped, and no per-derive agreement
     * key exists here):
     *  - [ResolvedAndroidPolicy.Advisory]: gate first, then one shot.
     *  - [ResolvedAndroidPolicy.Session]: one shot; on a stale keystore auth window
     *    ([HardwareKeyException.UserAuthenticationRequired]), prompt once (no `CryptoObject`) and
     *    retry once — the derive re-runs [agreeP256] fresh.
     *  - [ResolvedAndroidPolicy.PerUse]: unreachable for agreement; refused rather than silently
     *    downgraded to an unbound derive.
     */
    private suspend fun <T> gatedAgreementDh(
        policy: ResolvedAndroidPolicy,
        dh: suspend () -> T,
    ): T =
        when (policy) {
            is ResolvedAndroidPolicy.Advisory -> {
                if (!policy.gate.authorize()) throw AuthorizationFailed()
                dh()
            }

            is ResolvedAndroidPolicy.Session ->
                try {
                    dh()
                } catch (_: HardwareKeyException.UserAuthenticationRequired) {
                    if (!policy.unlock()) throw AuthorizationFailed()
                    dh()
                }

            is ResolvedAndroidPolicy.PerUse -> throw HardwareKeyException.AlgorithmNotEligible()
        }

    /**
     * Generates an OS-bound ECDH P-256 agreement key under [policy] (a [UserAuthenticationPolicy.Windowed]
     * → [ResolvedAndroidPolicy.Session] binding). Ephemeral: the scratch alias is deleted on close.
     * The `Windowed` bound at the [UserAuthenticatedKeyProvider] surface is what keeps a `PerUse`
     * agreement request from ever reaching here.
     */
    internal suspend fun generateKeyAgreementBound(policy: ResolvedAndroidPolicy): KeyAgreementKeyPair {
        if (!eligible(ProtectedKeyAlgorithm.EcdhP256)) throw HardwareKeyException.AlgorithmNotEligible()
        val alias = newAlias("ecdh")
        val placed = keystoreOp { generateAgreementKeyPair(alias, policy) }
        val publicKey =
            KeyAgreementPublicKey.of(KeyAgreementCurve.P256, uncompressedPoint(placed.key.public as ECPublicKey))
        return buildKeyAgreementPair(
            placed.key.private,
            publicKey,
            policy,
            placed.placement,
            onClose = { runCatching { keyStore.deleteEntry(alias) } },
        )
    }

    /** Generates an AES-GCM keystore key under [policy] — the shared path for unbound and OS-bound keys. */
    internal suspend fun generateAesGcmBound(
        policy: ResolvedAndroidPolicy,
        aesKeySizeBits: Int,
    ): AesGcmKey {
        requireValidAesSize(aesKeySizeBits)
        val alias = newAlias("aes")
        val placed = keystoreOp { generateAesKey(alias, aesKeySizeBits, policy) }
        // Ephemeral: the scratch alias is deleted when the returned key is closed.
        return buildAesGcmKey(
            placed.key,
            aesKeySizeBits,
            policy,
            placed.placement,
            onClose = { runCatching { keyStore.deleteEntry(alias) } },
        )
    }

    /**
     * Wraps an AndroidKeystore AES-GCM [key] handle as an [AesGcmKey]. Shared by the ephemeral
     * provider ([generateAesGcmBound], a scratch alias deleted on close) and the persistent
     * [AndroidKeystoreKeyStore] (a named alias whose [onClose] is a no-op, so the stored key survives
     * close). The seal/open closures drive a JCA `Cipher` over the keystore handle exactly as before —
     * the only difference between the two callers is the [onClose] lifecycle.
     */
    internal fun buildAesGcmKey(
        key: SecretKey,
        sizeBits: Int,
        policy: ResolvedAndroidPolicy,
        placement: HardwarePlacement,
        onClose: () -> Unit,
    ): AesGcmKey =
        ProtectedAesGcmKey(
            sizeBits = sizeBits,
            custody = placement.custody,
            secureElementFallback = placement.fallback,
            gatedSeal = { aad, plaintext, factory ->
                gatedOp(
                    policy = policy,
                    create = {
                        val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
                        // Randomized-encryption-required (the JCA default) ⇒ the keystore generates
                        // a fresh 12-byte GCM IV at init; read it back and frame it ahead of the
                        // ciphertext.
                        cipher.init(Cipher.ENCRYPT_MODE, key)
                        cipher
                    },
                    wrap = { BiometricPrompt.CryptoObject(it) },
                    use = { cipher ->
                        val nonce = cipher.iv
                        aad?.let { cipher.updateAADRemaining(it.slice()) }
                        val plaintextView = plaintext.slice()
                        val out = factory.allocate(nonce.size + plaintextView.remaining() + AEAD_TAG_BYTES)
                        out.writeBytes(nonce)
                        finalInto(cipher, plaintextView, out)
                        out.resetForRead()
                        out
                    },
                )
            },
            gatedOpen = { nonce, aad, ciphertextAndTag, factory ->
                requireNonce(nonce)
                requireTagged(ciphertextAndTag)
                gatedOp(
                    policy = policy,
                    create = {
                        val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
                        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(gcmTagBits, nonceBytes(nonce.slice())))
                        cipher
                    },
                    wrap = { BiometricPrompt.CryptoObject(it) },
                    use = { cipher ->
                        aad?.let { cipher.updateAADRemaining(it.slice()) }
                        val ctView = ciphertextAndTag.slice()
                        val out = factory.allocate(maxOf(0, ctView.remaining() - AEAD_TAG_BYTES))
                        openInto(cipher, ctView, out)
                        out.resetForRead()
                        out
                    },
                )
            },
            onClose = onClose,
        )

    /** Generates an ECDSA-P256 keystore key under [policy] — the shared path for unbound and OS-bound keys. */
    internal suspend fun generateSigningBound(
        policy: ResolvedAndroidPolicy,
        scheme: SignatureScheme,
    ): SigningKey {
        if (!eligible(scheme.toProtectedKeyAlgorithm())) throw HardwareKeyException.AlgorithmNotEligible()
        val alias = newAlias("ec")
        val placed = keystoreOp { generateEcKeyPair(alias, policy) }
        val verifyKey = VerifyKey.ecdsaP256(uncompressedPoint(placed.key.public as ECPublicKey))
        // Ephemeral: the scratch alias is deleted when the returned key is closed.
        return buildSigningKey(
            placed.key.private,
            verifyKey,
            policy,
            placed.placement,
            onClose = { runCatching { keyStore.deleteEntry(alias) } },
        )
    }

    /**
     * Wraps an AndroidKeystore EC private-key handle ([privateKey], with its captured [verifyKey]) as
     * a [SigningKey]. Shared by the ephemeral provider ([generateSigningBound]) and the persistent
     * [AndroidKeystoreKeyStore]; the sign closure drives a JCA `Signature` over the keystore handle,
     * and only [onClose] differs (scratch-alias delete vs. a no-op that keeps the stored key).
     */
    internal fun buildSigningKey(
        privateKey: PrivateKey,
        verifyKey: VerifyKey,
        policy: ResolvedAndroidPolicy,
        placement: HardwarePlacement,
        onClose: () -> Unit,
    ): SigningKey =
        ProtectedSigningKey(
            scheme = SignatureScheme.EcdsaP256,
            custody = placement.custody,
            secureElementFallback = placement.fallback,
            gatedSign = { message, factory ->
                gatedOp(
                    policy = policy,
                    create = {
                        Signature.getInstance(ECDSA_SHA256).apply { initSign(privateKey) }
                    },
                    wrap = { BiometricPrompt.CryptoObject(it) },
                    use = { signer ->
                        signer.update(message.slice().remainingBytes())
                        val der = signer.sign()
                        val out: PlatformBuffer = factory.allocate(der.size)
                        out.writeBytes(der)
                        out.resetForRead()
                        out
                    },
                )
            },
            verifyKey = verifyKey,
            onClose = onClose,
        )

    // --- persistent alias lifecycle (drives AndroidKeystoreKeyStore) -------------------------------
    //
    // A persistent key lives under a caller-named keystore alias (already namespaced by the store) and
    // is NOT deleted on close — onClose = {} — so it survives process restart until an explicit
    // delete. Metadata (kind + public key) is intrinsic to the keystore entry, so there is no sidecar.

    /**
     * The key under [alias] when it holds [expected], or one [generate]d under it when the keystore has none. The
     * lookup and the generation run in one keystore critical section, so no other caller of this provider can generate
     * under [alias] in between: two racing get-or-generates end with one key, and a generation that fails deletes only
     * what it made. [reattach] and [generate] run inside that section and must not enter it again.
     */
    internal suspend fun <K : Any> getOrGenerateEntry(
        alias: String,
        expected: ProtectedKeyAlgorithm,
        reattach: () -> K?,
        generate: () -> K,
    ): AliasEntry<K> =
        keystoreOp {
            when (val stored = storedAlgorithm(alias)) {
                null -> AliasEntry.Generated(generate())
                expected -> AliasEntry.Attached(reattach() ?: throw reattachFailed())
                else -> AliasEntry.HoldsOther(stored)
            }
        }

    /** Generates a persistent ECDSA-P256 key under [alias]; onClose is a no-op (the stored key survives). */
    internal fun generatePersistentSigningEntry(
        alias: String,
        spec: ProtectedKeySpec,
    ): SigningKey {
        val policy = ResolvedAndroidPolicy.Advisory(spec.authorization)
        val placed = generateEcKeyPair(alias, policy)
        val verifyKey = VerifyKey.ecdsaP256(uncompressedPoint(placed.key.public as ECPublicKey))
        return buildSigningKey(placed.key.private, verifyKey, policy, placed.placement, onClose = {})
    }

    /** Generates a persistent AES-GCM key under [alias]; onClose is a no-op (the stored key survives). */
    internal fun generatePersistentAesGcmEntry(
        alias: String,
        spec: ProtectedKeySpec,
    ): AesGcmKey {
        requireValidAesSize(spec.aesKeySizeBits)
        val policy = ResolvedAndroidPolicy.Advisory(spec.authorization)
        val placed = generateAesKey(alias, spec.aesKeySizeBits, policy)
        return buildAesGcmKey(placed.key, spec.aesKeySizeBits, policy, placed.placement, onClose = {})
    }

    /** Generates a persistent ECDH P-256 agreement key under [alias]; onClose is a no-op (the stored key survives). */
    internal fun generatePersistentKeyAgreementEntry(
        alias: String,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair {
        val policy = ResolvedAndroidPolicy.Advisory(spec.authorization)
        val placed = generateAgreementKeyPair(alias, policy)
        val publicKey =
            KeyAgreementPublicKey.of(KeyAgreementCurve.P256, uncompressedPoint(placed.key.public as ECPublicKey))
        return buildKeyAgreementPair(placed.key.private, publicKey, policy, placed.placement, onClose = {})
    }

    /** [reattachSigningEntry] in its own keystore critical section. */
    internal suspend fun reattachSigning(
        alias: String,
        spec: ProtectedKeySpec,
    ): SigningKey? = keystoreOp { reattachSigningEntry(alias, spec) }

    /** [reattachKeyAgreementEntry] in its own keystore critical section. */
    internal suspend fun reattachKeyAgreement(
        alias: String,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair? = keystoreOp { reattachKeyAgreementEntry(alias, spec) }

    /** [reattachAesGcmEntry] in its own keystore critical section. */
    internal suspend fun reattachAesGcm(
        alias: String,
        spec: ProtectedKeySpec,
    ): AesGcmKey? = keystoreOp { reattachAesGcmEntry(alias, spec) }

    /**
     * Re-attaches to a persisted signing key under [alias], or `null` if the alias is absent or holds
     * a key of a different kind (an AES entry, or an agreement key — both signing and agreement keys
     * are `PrivateKeyEntry`s, so the keystore-recorded purposes disambiguate). The gate is [spec]'s
     * advisory authorization; onClose is a no-op.
     */
    internal fun reattachSigningEntry(
        alias: String,
        spec: ProtectedKeySpec,
    ): SigningKey? {
        val privateEntry =
            (keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)
                ?.takeIf { privateEntryAlgorithm(it.privateKey) == ProtectedKeyAlgorithm.EcdsaP256 }
                ?: return null
        val placement = reattachedPlacement(alias, privateEntry.privateKey, ProtectedKeyAlgorithm.EcdsaP256)
        val verifyKey = VerifyKey.ecdsaP256(uncompressedPoint(privateEntry.certificate.publicKey as ECPublicKey))
        return buildSigningKey(
            privateEntry.privateKey,
            verifyKey,
            ResolvedAndroidPolicy.Advisory(spec.authorization),
            placement,
            onClose = {},
        )
    }

    /**
     * Re-attaches to a persisted agreement key under [alias], or `null` if the alias is absent or
     * holds a key of a different kind (purpose-checked, mirroring [reattachSigningEntry]). The gate is
     * [spec]'s advisory authorization; onClose is a no-op.
     */
    internal fun reattachKeyAgreementEntry(
        alias: String,
        spec: ProtectedKeySpec,
    ): KeyAgreementKeyPair? {
        val privateEntry =
            (keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)
                ?.takeIf { privateEntryAlgorithm(it.privateKey) == ProtectedKeyAlgorithm.EcdhP256 }
                ?: return null
        val placement = reattachedPlacement(alias, privateEntry.privateKey, ProtectedKeyAlgorithm.EcdhP256)
        val point = uncompressedPoint(privateEntry.certificate.publicKey as ECPublicKey)
        val publicKey = KeyAgreementPublicKey.of(KeyAgreementCurve.P256, point)
        return buildKeyAgreementPair(
            privateEntry.privateKey,
            publicKey,
            ResolvedAndroidPolicy.Advisory(spec.authorization),
            placement,
            onClose = {},
        )
    }

    /** Re-attaches to a persisted AES-GCM key under [alias], or `null` if absent / a different kind. */
    internal fun reattachAesGcmEntry(
        alias: String,
        spec: ProtectedKeySpec,
    ): AesGcmKey? {
        val secretEntry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry ?: return null
        val placement = reattachedPlacement(alias, secretEntry.secretKey, ProtectedKeyAlgorithm.AesGcm)
        val sizeBits = aesKeySizeBits(secretEntry.secretKey)
        return buildAesGcmKey(
            secretEntry.secretKey,
            sizeBits,
            ResolvedAndroidPolicy.Advisory(spec.authorization),
            placement,
            onClose = {},
        )
    }

    /**
     * What `KeyStore.getKey` says is under [alias]. The lookup goes through `getKey`, not `containsAlias`:
     * `containsAlias` answers `false` for any keystore error (android-36 `AndroidKeyStoreSpi.getKeyMetadata` logs and
     * returns `null`), while `getKey` answers `null` only for `KEY_NOT_FOUND` and throws otherwise.
     */
    private fun lookup(alias: String): EntryLookup =
        try {
            when (val key = keyStore.getKey(alias, null)) {
                null -> EntryLookup.Absent
                is PrivateKey -> EntryLookup.Private(privateEntryAlgorithm(key))
                is SecretKey -> EntryLookup.Secret
                else -> EntryLookup.OtherType
            }
        } catch (e: java.security.UnrecoverableKeyException) {
            EntryLookup.Unloadable(e.cause is KeyStoreException)
        } catch (_: java.security.KeyStoreException) {
            EntryLookup.Unloadable(keystoreFailed = true)
        }

    /**
     * The [ProtectedKeyAlgorithm] stored under [alias], or `null` only when the keystore says no key is there. Every
     * other answer refuses (see [entryVerdict]); nothing is ever generated over an alias that could not be read.
     */
    private fun storedAlgorithm(alias: String): ProtectedKeyAlgorithm? =
        when (val verdict = entryVerdict(lookup(alias))) {
            EntryVerdict.Empty -> null
            is EntryVerdict.Holds -> verdict.algorithm
            is EntryVerdict.Refuse -> throw verdict.failure
        }

    /** Whether any keystore entry exists under [alias]; a keystore that cannot answer throws, never answers `false`. */
    internal suspend fun containsAlias(alias: String): Boolean = keystoreOp { entryPresent(lookup(alias)) }

    /** Deletes [alias]; `true` if an entry was removed, `false` if none existed, a typed failure when unknown. */
    internal suspend fun deleteAlias(alias: String): Boolean =
        keystoreOp {
            if (entryPresent(lookup(alias))) {
                try {
                    keyStore.deleteEntry(alias)
                } catch (_: java.security.KeyStoreException) {
                    throw deleteRefused()
                }
                true
            } else {
                false
            }
        }

    /** Every keystore alias beginning with [prefix] (the store's namespace). */
    internal suspend fun listAliases(prefix: String): Set<String> =
        keystoreOp { keyStore.aliases().toList().filterTo(mutableSetOf()) { it.startsWith(prefix) } }

    /** The bit length of an AndroidKeystore AES [key] handle, read back from its [KeyInfo]. */
    private fun aesKeySizeBits(key: SecretKey): Int {
        val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEY_STORE)
        return (factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo).keySize
    }

    // --- user-authentication policy dispatch -------------------------------------------------------

    /**
     * Runs one keystore op under the key's resolved user-auth [policy]. [create] initializes the
     * JCA handle (`Cipher`/`Signature`) and [use] drives it to completion — both inside
     * [keystoreOp]; the prompt (when any) happens strictly *outside* the keystore lock.
     *
     *  - [ResolvedAndroidPolicy.Advisory]: gate first, then one shot.
     *  - [ResolvedAndroidPolicy.Session]: one shot; when the keystore reports the auth window
     *    stale ([HardwareKeyException.UserAuthenticationRequired], mapped from
     *    `UserNotAuthenticatedException`), prompt once and retry once — a fresh handle, because
     *    the stale one is unusable.
     *  - [ResolvedAndroidPolicy.PerUse]: initialize the handle, authorize *that exact handle* via
     *    `CryptoObject`, then drive it. The variant carries its [BiometricAuthorization] by
     *    construction — resolved at generation, so no cast and no nullable state here.
     */
    private suspend fun <C : Any, T> gatedOp(
        policy: ResolvedAndroidPolicy,
        create: () -> C,
        wrap: (C) -> BiometricPrompt.CryptoObject,
        use: (C) -> T,
    ): T =
        when (policy) {
            is ResolvedAndroidPolicy.Advisory -> {
                if (!policy.gate.authorize()) throw AuthorizationFailed()
                keystoreOp { use(create()) }
            }

            is ResolvedAndroidPolicy.Session ->
                try {
                    keystoreOp { use(create()) }
                } catch (_: HardwareKeyException.UserAuthenticationRequired) {
                    if (!policy.unlock()) throw AuthorizationFailed()
                    keystoreOp { use(create()) }
                }

            is ResolvedAndroidPolicy.PerUse -> {
                val crypto = keystoreOp { create() }
                if (!policy.prompt.authenticate(policy.method, wrap(crypto))) throw AuthorizationFailed()
                keystoreOp { use(crypto) }
            }
        }

    // --- keystore serialization ------------------------------------------------------------------

    /**
     * Runs a keystore [block] serialized (one in flight) and off the calling thread, normalizing any
     * keystore-originated failure to a [HardwareKeyException]. A [CryptoException] thrown *inside*
     * (e.g. the opaque [VerificationFailed] from [openInto], or [AuthorizationFailed]) passes through
     * untouched so a bad-tag open never leaks an oracle and is never relabeled a hardware failure.
     */
    private suspend fun <T> keystoreOp(block: () -> T): T =
        keystoreLock.withLock {
            withContext(keystoreDispatcher) {
                synchronized(keystoreMonitor) { mappingKeystoreFailures(block) }
            }
        }

    // --- keystore generation ---------------------------------------------------------------------

    private fun generateAesKey(
        alias: String,
        sizeBits: Int,
        policy: ResolvedAndroidPolicy,
    ): Placed<SecretKey> =
        generatePlaced(alias, ProtectedKeyAlgorithm.AesGcm, { it }) { request ->
            KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
                .apply {
                    init(aesSpec(alias, sizeBits, request, policy))
                }.generateKey()
        }

    private fun generateEcKeyPair(
        alias: String,
        policy: ResolvedAndroidPolicy,
    ): Placed<java.security.KeyPair> =
        generatePlaced(alias, ProtectedKeyAlgorithm.EcdsaP256, { it.private }) { request ->
            KeyPairGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
                .apply {
                    initialize(ecSpec(alias, request, policy))
                }.generateKeyPair()
        }

    private fun generateAgreementKeyPair(
        alias: String,
        policy: ResolvedAndroidPolicy,
    ): Placed<java.security.KeyPair> =
        generatePlaced(alias, ProtectedKeyAlgorithm.EcdhP256, { it.private }) { request ->
            KeyPairGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
                .apply {
                    initialize(agreementSpec(alias, request, policy))
                }.generateKeyPair()
        }

    /** Generates under [alias], StrongBox first (see [generateWithStrongBoxFallback]), and confirms where it landed. */
    private inline fun <T> generatePlaced(
        alias: String,
        alg: ProtectedKeyAlgorithm,
        keyOf: (T) -> java.security.Key,
        build: (StrongBoxRequest) -> T,
    ): Placed<T> {
        val generated = generateWithStrongBoxFallback(build)
        var confirmed = false
        try {
            val placement =
                confirmedPlacement(alias, keyOf(generated.key), alg, KeyOrigin.JustGenerated(generated.fallback))
            confirmed = true
            return Placed(generated.key, placement)
        } finally {
            // A key whose placement could not even be read is not handed out, and not left behind under its alias.
            if (!confirmed) deleteQuietly(alias)
        }
    }

    /**
     * Builds a key in StrongBox and, when [strongBoxRefusal] says so, in the TEE: a transient StrongBox refusal is
     * tried in StrongBox again up to [STRONGBOX_ATTEMPTS] times, every other refusal goes to the TEE at once. The TEE
     * key carries the reason and the keystore's code ([SecureElementFallback.ToWeakerSecureTier]); a TEE failure is normalized to a
     * [HardwareKeyException].
     */
    private inline fun <T> generateWithStrongBoxFallback(build: (StrongBoxRequest) -> T): Attempted<T> {
        var attempt = 1
        while (true) {
            val refusal =
                try {
                    return Attempted(build(StrongBoxRequest.StrongBox), SecureElementFallback.None)
                } catch (e: GeneralSecurityException) {
                    e
                } catch (e: ProviderException) {
                    e
                }
            when (val decision = strongBoxRefusal(keystoreFailureFacts(refusal), attempt)) {
                StrongBoxRefusal.RetryStrongBox -> attempt++
                is StrongBoxRefusal.FallBackToTee ->
                    return mappingKeystoreFailures { Attempted(build(StrongBoxRequest.Tee), decision.fallback) }
            }
        }
    }

    /**
     * The custody [key] (stored under [alias]) has, read from its `KeyInfo` — the keystore's own report, which only
     * attestation verified off the device proves. A key outside secure hardware is refused with
     * [InsufficientKeyCustody]; it is deleted only when [origin] says this call just generated it, never when it was
     * persisted earlier and merely reattached.
     */
    private fun confirmedPlacement(
        alias: String,
        key: java.security.Key,
        alg: ProtectedKeyAlgorithm,
        origin: KeyOrigin,
    ): HardwarePlacement {
        val info = keyInfoOf(key)
        val placement =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                placementOfSecurityLevel(info.securityLevel)
            } else {
                @Suppress("DEPRECATION")
                placementOfLegacyKeyInfo(info.isInsideSecureHardware)
            }
        return when (val verdict = custodyVerdict(placement, origin)) {
            is CustodyVerdict.Accept -> verdict.placement
            is CustodyVerdict.DeleteAndRefuse -> {
                keyStore.deleteEntry(alias)
                throw notHardware(alg, verdict.offered)
            }
            is CustodyVerdict.Refuse -> throw notHardware(alg, verdict.offered)
        }
    }

    /** A reattached key is refused when outside secure hardware, and never deleted for it. */
    private fun reattachedPlacement(
        alias: String,
        key: java.security.Key,
        alg: ProtectedKeyAlgorithm,
    ): HardwarePlacement = confirmedPlacement(alias, key, alg, KeyOrigin.Reattached)

    /** Deletes [alias] after a failed generation; a keystore that cannot delete it leaves nothing more to do. */
    private fun deleteQuietly(alias: String) {
        try {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        } catch (_: java.security.KeyStoreException) {
            // The generation's own failure is what the caller sees.
        }
    }

    /**
     * Probes whether the keystore actually backs ECDH P-256 agreement keys in secure hardware:
     * generates a throwaway `PURPOSE_AGREE_KEY` key, confirms its `KeyInfo` level, and runs a full
     * keystore ECDH against a fresh software peer. The end-to-end agreement matters — a device may
     * accept the generation spec and still fail the `KeyAgreement` op, or place the key in a software
     * KeyMint — so generation alone would over-promise. Pre-31 the purpose does not exist. A transient
     * keystore refusal is [Settlement.Unsettled], so a busy keystore is not taken for one without
     * agreement.
     */
    private fun probeAgreement(): Settlement<Boolean> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Settlement.Settled(false)
        return synchronized(keystoreMonitor) {
            val alias = newAlias("probe-ecdh")
            try {
                val keyPair =
                    KeyPairGenerator
                        .getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
                        .apply {
                            initialize(agreementSpec(alias, StrongBoxRequest.Tee, probeAdvisoryPolicy))
                        }.generateKeyPair()
                when (placementOfSecurityLevel(keyInfoOf(keyPair.private).securityLevel)) {
                    is KeystorePlacement.NotSecureHardware -> Settlement.Settled(false)
                    is KeystorePlacement.SecureHardware -> Settlement.Settled(agreesWithSoftwarePeer(keyPair.private))
                }
            } catch (e: GeneralSecurityException) {
                probeRefusal(e)
            } catch (e: ProviderException) {
                probeRefusal(e)
            } finally {
                runCatching { keyStore.deleteEntry(alias) }
            }
        }
    }

    private fun agreesWithSoftwarePeer(privateKey: PrivateKey): Boolean {
        val peer =
            KeyPairGenerator
                .getInstance("EC")
                .apply { initialize(ECGenParameterSpec(SECP256R1)) }
                .generateKeyPair()
        val ka = KeyAgreement.getInstance(ECDH, ANDROID_KEY_STORE)
        ka.init(privateKey)
        ka.doPhase(peer.public, true)
        val secret = ka.generateSecret()
        val ok = secret.size == P256_FIELD_BYTES
        secret.fill(0)
        return ok
    }

    /** A refused probe: transient ⇒ ask again next time; anything else ⇒ no hardware agreement on this device. */
    private fun probeRefusal(failure: Throwable): Settlement<Boolean> =
        if (keystoreFailureFacts(failure).any { it is KeystoreFailureFact.Coded && it.transientFailure }) {
            Settlement.Unsettled(false)
        } else {
            Settlement.Settled(false)
        }
}

/** Which element a key generation asks for. */
internal sealed interface StrongBoxRequest {
    val strongBoxBacked: Boolean

    data object StrongBox : StrongBoxRequest {
        override val strongBoxBacked: Boolean get() = true
    }

    data object Tee : StrongBoxRequest {
        override val strongBoxBacked: Boolean get() = false
    }
}

/** A keystore generation's result, and the fallback that produced it when StrongBox refused. */
private class Attempted<T>(
    val key: T,
    val fallback: SecureElementFallback,
)

/** Where a key lives, as its `KeyInfo` reports, and why it is not in StrongBox when it asked to be. */
internal data class HardwarePlacement(
    val custody: KeyCustody.NonExportable.Hardware,
    val fallback: SecureElementFallback,
)

/** What a get-or-generate found under an alias. */
internal sealed interface AliasEntry<out K> {
    class Attached<out K>(
        val key: K,
    ) : AliasEntry<K>

    class Generated<out K>(
        val key: K,
    ) : AliasEntry<K>

    /** The alias holds a key of [stored], not the one asked for. */
    class HoldsOther(
        val stored: ProtectedKeyAlgorithm,
    ) : AliasEntry<Nothing>
}

/** A generated keystore key with its [HardwarePlacement]. */
internal class Placed<T>(
    val key: T,
    val placement: HardwarePlacement,
)

private fun newAlias(kind: String): String = "$ALIAS_PREFIX$kind-${UUID.randomUUID()}"

/** Rejects an AES key size a secure element does not back (only 128 / 256 bit). */
private fun requireValidAesSize(sizeBits: Int) {
    val valid =
        sizeBits == AES_128_KEY_BYTES * Byte.SIZE_BITS ||
            sizeBits == AES_256_KEY_BYTES * Byte.SIZE_BITS
    if (!valid) throw HardwareKeyException.UnsupportedHardwareKey()
}

// ANDROID_KEY_STORE / SECP256R1 are shared with the agreement helpers in
// HardwareKeyAgreement.android.kt (internal, not private); the SEC1/field-width constants stay
// private because same-named private constants already exist in other files of this package.
internal const val ANDROID_KEY_STORE = "AndroidKeyStore"
private const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"
private const val ECDSA_SHA256 = "SHA256withECDSA"
internal const val SECP256R1 = "secp256r1"
private const val ALIAS_PREFIX = "com.ditchoom.buffer.crypto.hw."
private const val P256_FIELD_BYTES = 32
private const val SEC1_UNCOMPRESSED = 0x04

// --- stateless keystore helpers (file-level: no provider state, kept off the class) --------------

/** Unbound throwaway-key policy for the agreement probe. */
private val probeAdvisoryPolicy = ResolvedAndroidPolicy.Advisory(HardwareAuthorization { true })

private fun aesSpec(
    alias: String,
    sizeBits: Int,
    request: StrongBoxRequest,
    policy: ResolvedAndroidPolicy,
): KeyGenParameterSpec =
    KeyGenParameterSpec
        .Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setKeySize(sizeBits)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        // Randomized encryption stays *required* (the default): the keystore picks a fresh GCM IV
        // per seal, which the witness reads back and frames (see ProtectedAesGcmKey.gatedSeal). The
        // library never supplies a seal nonce — one reused GCM (key, nonce) pair is catastrophic.
        .setIsStrongBoxBacked(request.strongBoxBacked)
        .apply { applyUserAuth(policy) }
        .build()

private fun ecSpec(
    alias: String,
    request: StrongBoxRequest,
    policy: ResolvedAndroidPolicy,
): KeyGenParameterSpec =
    KeyGenParameterSpec
        .Builder(alias, KeyProperties.PURPOSE_SIGN)
        .setAlgorithmParameterSpec(ECGenParameterSpec(SECP256R1))
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setIsStrongBoxBacked(request.strongBoxBacked)
        .apply { applyUserAuth(policy) }
        .build()

/**
 * Runs [block], normalizing any keystore-originated failure to a [HardwareKeyException]. A
 * [CryptoException] thrown inside (the opaque [VerificationFailed] from [openInto], or
 * [AuthorizationFailed]) passes through untouched so a bad-tag open never leaks an oracle and is
 * never relabeled a hardware failure.
 */
private inline fun <T> mappingKeystoreFailures(block: () -> T): T =
    try {
        block()
    } catch (e: CryptoException) {
        throw e // VerificationFailed / AuthorizationFailed / already-mapped HardwareKeyException
    } catch (e: GeneralSecurityException) {
        throw mapKeystoreFailure(e)
    } catch (e: ProviderException) {
        throw mapKeystoreFailure(e)
    }

/**
 * Normalizes a keystore-originated failure to a sealed [HardwareKeyException] state. Walks the cause
 * chain because the JCA frequently wraps the real `android.security.KeyStoreException` inside a
 * [ProviderException]. Unrecognized failures collapse to [HardwareKeyException.UnsupportedHardwareKey]
 * (a public, non-secret outcome — never an oracle).
 */
private fun mapKeystoreFailure(failure: Throwable): HardwareKeyException {
    var cur: Throwable? = failure
    while (cur != null) {
        val mapped =
            when (cur) {
                is UserNotAuthenticatedException -> HardwareKeyException.UserAuthenticationRequired()
                is KeyPermanentlyInvalidatedException -> HardwareKeyException.KeyInvalidated()
                is StrongBoxUnavailableException -> HardwareKeyException.SecureElementUnavailable()
                is KeyStoreException ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && cur.isTransientFailure) {
                        HardwareKeyException.TransientHardwareFailure(retryable = true)
                    } else {
                        HardwareKeyException.UnsupportedHardwareKey()
                    }
                else -> null
            }
        if (mapped != null) return mapped
        cur = cur.cause
    }
    return HardwareKeyException.UnsupportedHardwareKey()
}

/** Encodes an EC public key as an uncompressed SEC1 point (`04 ‖ X ‖ Y`) for [VerifyKey]. */
private fun uncompressedPoint(pub: ECPublicKey): ReadBuffer {
    val field = P256_FIELD_BYTES
    val out = ByteArray(1 + 2 * field)
    out[0] = SEC1_UNCOMPRESSED.toByte()
    fixedBigEndian(pub.w.affineX, field).copyInto(out, 1)
    fixedBigEndian(pub.w.affineY, field).copyInto(out, 1 + field)
    return BufferFactory.Default.wrap(out)
}

/** A nonnegative [BigInteger] as exactly [n] big-endian bytes (left-padded / sign-byte stripped). */
private fun fixedBigEndian(
    value: BigInteger,
    n: Int,
): ByteArray {
    val be = value.toByteArray()
    return when {
        be.size == n -> be
        be.size == n + 1 && be[0].toInt() == 0 -> be.copyOfRange(1, be.size)
        be.size < n -> ByteArray(n - be.size) + be
        else -> be.copyOfRange(be.size - n, be.size)
    }
}

/**
 * The Android Keystore provider, resolved through a [SettlingLazy]. A device/emulator always
 * registers an `AndroidKeyStore` provider, but a host-JVM unit-test run — which inherits the common
 * test suite — has none, which is settled as [CapabilityFinding.Keystore.ProviderMissing] and keeps
 * [CryptoCapabilities.hardware] honestly [HardwareSupport.Unavailable] off-device. A registered
 * provider whose keystore fails to load is [CapabilityFinding.Keystore.LoadFailed] for that attempt
 * only: the next resolution tries again instead of pinning the failure for the process lifetime.
 */
private val androidResolution =
    SettlingLazy<ProtectedKeyResolution>(PROBE_RETRY_AFTER, TimeSource.Monotonic) {
        if (!androidKeystoreProviderRegistered()) {
            Settlement.Settled(keystoreRefused(CapabilityFinding.Keystore.ProviderMissing))
        } else {
            try {
                Settlement.Settled(
                    ProtectedKeyResolution.Available(
                        ProtectedKeyBackend.AndroidKeystore,
                        AndroidKeystoreHardwareKeyProvider(
                            KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) },
                            Dispatchers.Default,
                        ),
                    ),
                )
            } catch (_: GeneralSecurityException) {
                keystoreLoadFailed()
            } catch (_: java.io.IOException) {
                keystoreLoadFailed()
            } catch (_: ProviderException) {
                keystoreLoadFailed()
            } catch (
                @Suppress("TooGenericExceptionCaught") _: RuntimeException,
            ) {
                // A provider that is registered but whose keystore throws an unchecked failure while loading: still a
                // load failure for this attempt, never a crash of the resolution and never a weaker store.
                keystoreLoadFailed()
            }
        }
    }

private fun keystoreLoadFailed(): Settlement<ProtectedKeyResolution> =
    Settlement.Unsettled(keystoreRefused(CapabilityFinding.Keystore.LoadFailed))

internal actual fun platformProtectedKeyResolution(): ProtectedKeyResolution = androidResolution.get()
