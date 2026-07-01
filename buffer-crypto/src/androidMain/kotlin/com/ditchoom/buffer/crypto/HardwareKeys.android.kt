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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
 * Eligibility mirrors what a secure element actually backs: AES-256/128-GCM and ECDSA P-256.
 * (Ed25519 / P-384 / P-521 are not offered — see [eligible].)
 *
 * **Robustness shape (all driven by real device behaviour):**
 *  - **StrongBox fallback is exception-broad.** A device that lacks (or under-provisions) a secure
 *    element does not reliably throw [StrongBoxUnavailableException]: Pixel 4 surfaces a plain
 *    `ProviderException` and Samsung S24/Android 14 a `KeyStoreException` (code 4) even for a
 *    spec-guaranteed AES-256-GCM key. So the StrongBox attempt catches the whole
 *    `GeneralSecurityException` / `ProviderException` family and retries in the TEE rather than
 *    crashing; a terminal TEE failure is normalized to a [HardwareKeyException] state.
 *  - **The keystore generates the GCM IV.** AES keys are *randomized-encryption-required* (the JCA
 *    default), so `Cipher.init(ENCRYPT_MODE, key)` makes the keystore mint a fresh IV; the seal reads
 *    it back via [Cipher.getIV] and frames `nonce ‖ ciphertext ‖ tag`. The library never supplies a
 *    seal IV — a single reused GCM nonce is catastrophic, and letting the element choose matches
 *    Signal / Tink / AndroidX.
 *  - **`dedicatedSecureElement` is confirmed, not inferred.** On API 31+ the probe reads back
 *    `KeyInfo.getSecurityLevel() == SECURITY_LEVEL_STRONGBOX` (a device may honor a StrongBox request
 *    silently in the TEE); pre-31 cannot query the level, so it stays best-effort (no throw ⇒ assume).
 *  - **All keystore access is serialized + off the calling thread.** AndroidKeyStore is not
 *    thread-safe and StrongBox ops are slow, so generation and every per-op cipher/signature run
 *    under a [Mutex] on [Dispatchers.Default]. The auth gate ([HardwareAuthorization.authorize]) is
 *    evaluated *outside* the lock — a biometric prompt must not hold the keystore mutex.
 *
 * **User authentication** follows [HardwareKeySpec.userAuthentication]:
 *  - [UserAuthenticationRequirement.None] — no OS binding; the [HardwareAuthorization] gate is
 *    advisory, evaluated before every op (the pre-biometrics behavior, and the default — this is
 *    what keeps unattended conformance runs green).
 *  - [UserAuthenticationRequirement.Session] — the key is generated with
 *    `setUserAuthenticationRequired(true)` + a validity window (`setUserAuthenticationParameters`
 *    on API 30+, the deprecated validity-duration seconds on 28/29), so the *keystore* refuses a
 *    stale op with `UserNotAuthenticatedException`. The provider then prompts via the gate
 *    (a [BiometricAuthorization] shows a real prompt; a plain closure just decides) and retries
 *    exactly once — an already-authenticated user is never re-prompted.
 *  - [UserAuthenticationRequirement.PerUse] — auth-per-use binding (timeout 0 /
 *    validity -1), which Android only honors through a `BiometricPrompt.CryptoObject`
 *    wrapping the exact `Cipher`/`Signature`. Generation therefore requires a
 *    [BiometricAuthorization] (the library ships [BiometricPromptAuthenticator]); anything else
 *    throws [HardwareKeyException.UserAuthenticatorRequired] at generation.
 */
internal class AndroidKeystoreHardwareKeyProvider : HardwareKeyProvider {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private val gcmTagBits = AEAD_TAG_BYTES * Byte.SIZE_BITS

    /**
     * Serializes suspend keystore ops (so only one [Dispatchers.Default] worker is ever blocked on a
     * slow StrongBox round-trip at a time). [keystoreMonitor] additionally excludes the non-suspend
     * lazy [probeStrongBox] — which can't take the suspend [Mutex] — so the probe and live ops never
     * touch the keystore concurrently. Lock order is always Mutex → monitor; the probe takes only the
     * monitor, so there is no cycle.
     */
    private val keystoreLock = Mutex()
    private val keystoreMonitor = Any()

    /**
     * `true` once a `setIsStrongBoxBacked(true)` generation succeeds *and*, on API 31+, the resulting
     * key reports `SECURITY_LEVEL_STRONGBOX` — i.e. a dedicated StrongBox secure element actually
     * backs it. Probed once (generate-and-delete a throwaway key) because the SPI carries no `Context`
     * with which to query `FEATURE_STRONGBOX_KEYSTORE`.
     */
    override val dedicatedSecureElement: Boolean by lazy { probeStrongBox() }

    override fun eligible(alg: HardwareAlgorithm): Boolean =
        when (alg) {
            HardwareAlgorithm.AesGcm, HardwareAlgorithm.EcdsaP256 -> true
            else -> false
        }

    override suspend fun generateAesGcm(spec: HardwareKeySpec): AesGcmKey {
        val keyBits = spec.aesKeySizeBits
        if (keyBits != AES_128_KEY_BYTES * Byte.SIZE_BITS && keyBits != AES_256_KEY_BYTES * Byte.SIZE_BITS) {
            throw HardwareKeyException.UnsupportedHardwareKey()
        }
        val policy = resolvePolicy(spec)
        val alias = newAlias("aes")
        val key = keystoreOp { generateAesKey(alias, spec.aesKeySizeBits, spec.userAuthentication) }
        return HardwareAesGcmKey(
            sizeBits = spec.aesKeySizeBits,
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
            onClose = { runCatching { keyStore.deleteEntry(alias) } },
        )
    }

    override suspend fun generateSigning(
        scheme: SignatureScheme,
        spec: HardwareKeySpec,
    ): SigningKey {
        if (!eligible(scheme.toHardwareAlgorithm())) throw HardwareKeyException.AlgorithmNotEligible()
        val policy = resolvePolicy(spec)
        val alias = newAlias("ec")
        val keyPair = keystoreOp { generateEcKeyPair(alias, spec.userAuthentication) }
        val privateKey = keyPair.private
        val verifyKey = VerifyKey.ecdsaP256(uncompressedPoint(keyPair.public as ECPublicKey))
        return HardwareSigningKey(
            scheme = SignatureScheme.EcdsaP256,
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
            onClose = { runCatching { keyStore.deleteEntry(alias) } },
        )
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
            withContext(Dispatchers.Default) {
                synchronized(keystoreMonitor) { mappingKeystoreFailures(block) }
            }
        }

    // --- keystore generation ---------------------------------------------------------------------

    private fun generateAesKey(
        alias: String,
        sizeBits: Int,
        req: UserAuthenticationRequirement,
    ): SecretKey =
        generateWithStrongBoxFallback { strongBox ->
            KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
                .apply {
                    init(aesSpec(alias, sizeBits, strongBox, req))
                }.generateKey()
        }

    private fun aesSpec(
        alias: String,
        sizeBits: Int,
        strongBox: Boolean,
        req: UserAuthenticationRequirement,
    ): KeyGenParameterSpec =
        KeyGenParameterSpec
            .Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setKeySize(sizeBits)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            // Randomized encryption stays *required* (the default): the keystore picks a fresh GCM IV
            // per seal, which the witness reads back and frames (see HardwareAesGcmKey.gatedSeal). The
            // library never supplies a seal nonce — one reused GCM (key, nonce) pair is catastrophic.
            .setIsStrongBoxBacked(strongBox)
            .apply { applyUserAuth(req) }
            .build()

    private fun generateEcKeyPair(
        alias: String,
        req: UserAuthenticationRequirement,
    ): java.security.KeyPair =
        generateWithStrongBoxFallback { strongBox ->
            KeyPairGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
                .apply {
                    initialize(ecSpec(alias, strongBox, req))
                }.generateKeyPair()
        }

    private fun ecSpec(
        alias: String,
        strongBox: Boolean,
        req: UserAuthenticationRequirement,
    ): KeyGenParameterSpec =
        KeyGenParameterSpec
            .Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec(SECP256R1))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .apply { applyUserAuth(req) }
            .build()

    /**
     * Builds a key with `setIsStrongBoxBacked(true)`, falling back to the TEE on *any* StrongBox
     * rejection. Crucially this is not gated on [StrongBoxUnavailableException]: real devices reject
     * StrongBox with a plain [ProviderException] / [KeyStoreException] (Pixel 4: `UNSUPPORTED_KEY_SIZE`;
     * Samsung S24/Android 14: code 4 for spec-guaranteed AES-256-GCM), so the StrongBox attempt swallows
     * the whole [GeneralSecurityException] / [ProviderException] family and retries. A terminal TEE
     * failure is normalized to a [HardwareKeyException].
     */
    private inline fun <T> generateWithStrongBoxFallback(build: (strongBox: Boolean) -> T): T {
        try {
            return build(true)
        } catch (_: GeneralSecurityException) {
            // StrongBox rejected the spec (KeyStoreException / InvalidAlgorithmParameterException) ⇒ TEE.
        } catch (_: ProviderException) {
            // StrongBoxUnavailableException or a plain ProviderException StrongBox rejection ⇒ TEE.
        }
        return try {
            build(false)
        } catch (e: GeneralSecurityException) {
            throw mapKeystoreFailure(e)
        } catch (e: ProviderException) {
            throw mapKeystoreFailure(e)
        }
    }

    private fun probeStrongBox(): Boolean =
        synchronized(keystoreMonitor) {
            val alias = newAlias("probe")
            try {
                val keyPair =
                    KeyPairGenerator
                        .getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
                        .apply { initialize(ecSpec(alias, strongBox = true, req = UserAuthenticationRequirement.None)) }
                        .generateKeyPair()
                // A device may honor a StrongBox request silently in the TEE, so confirm the level
                // where the OS exposes it (API 31+); pre-31 keep the best-effort "no throw ⇒ assume".
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    securityLevelIsStrongBox(keyPair.private)
                } else {
                    true
                }
            } catch (_: Throwable) {
                // StrongBoxUnavailableException (no secure element) or any provisioning failure ⇒ not a
                // dedicated secure element. AES-GCM / ECDSA still work TEE-backed.
                false
            } finally {
                runCatching { keyStore.deleteEntry(alias) }
            }
        }
}

private fun newAlias(kind: String): String = "$ALIAS_PREFIX$kind-${UUID.randomUUID()}"

private const val ANDROID_KEY_STORE = "AndroidKeyStore"
private const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"
private const val ECDSA_SHA256 = "SHA256withECDSA"
private const val SECP256R1 = "secp256r1"
private const val ALIAS_PREFIX = "com.ditchoom.buffer.crypto.hw."
private const val P256_FIELD_BYTES = 32
private const val SEC1_UNCOMPRESSED = 0x04

/**
 * The key's user-auth policy resolved once at generation. Each variant carries exactly what its
 * op path needs — [PerUse] holds its [BiometricAuthorization] by construction, so "per-use key
 * without a CryptoObject-capable prompt" is unrepresentable (no cast, no nullable downstream).
 */
private sealed interface ResolvedAndroidPolicy {
    class Advisory(
        val gate: HardwareAuthorization,
    ) : ResolvedAndroidPolicy

    /**
     * [gate] may or may not be a real prompt: a [BiometricAuthorization] shows the OS prompt on a
     * stale window ([unlock]); a plain closure just decides — the keystore still enforces the
     * binding either way.
     */
    class Session(
        val method: UserAuthenticationMethod,
        val gate: HardwareAuthorization,
    ) : ResolvedAndroidPolicy {
        suspend fun unlock(): Boolean =
            when (gate) {
                is BiometricAuthorization -> gate.authenticate(method, cryptoObject = null)
                else -> gate.authorize()
            }
    }

    class PerUse(
        val method: UserAuthenticationMethod,
        val prompt: BiometricAuthorization,
    ) : ResolvedAndroidPolicy
}

/**
 * Resolves [HardwareKeySpec.userAuthentication] + [HardwareKeySpec.authorization] into a
 * [ResolvedAndroidPolicy], failing generation with the typed
 * [HardwareKeyException.UserAuthenticatorRequired] when per-use binding is requested without a
 * [BiometricAuthorization] (Android only honors auth-per-use through a
 * `BiometricPrompt.CryptoObject`; a plain closure cannot put the OS prompt on screen, so the key
 * would be permanently unusable).
 */
private fun resolvePolicy(spec: HardwareKeySpec): ResolvedAndroidPolicy =
    when (val req = spec.userAuthentication) {
        UserAuthenticationRequirement.None -> ResolvedAndroidPolicy.Advisory(spec.authorization)
        is UserAuthenticationRequirement.Session -> ResolvedAndroidPolicy.Session(req.method, spec.authorization)
        is UserAuthenticationRequirement.PerUse ->
            ResolvedAndroidPolicy.PerUse(
                req.method,
                spec.authorization as? BiometricAuthorization
                    ?: throw HardwareKeyException.UserAuthenticatorRequired(),
            )
    }

// --- stateless keystore helpers (file-level: no provider state, kept off the class) --------------

/**
 * Applies OS-level user-authentication binding to a keystore key at generation. On API 30+ the
 * typed `setUserAuthenticationParameters` expresses both the window and the allowed authenticator
 * classes; 28/29 only have the deprecated validity-duration seconds, where `-1` means
 * auth-per-use (biometric + `CryptoObject` only) and the authenticator classes cannot be narrowed.
 */
private fun KeyGenParameterSpec.Builder.applyUserAuth(req: UserAuthenticationRequirement) {
    when (req) {
        UserAuthenticationRequirement.None -> return
        is UserAuthenticationRequirement.Session -> {
            setUserAuthenticationRequired(true)
            val seconds =
                req.validity.inWholeSeconds
                    .coerceIn(1L, Int.MAX_VALUE.toLong())
                    .toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setUserAuthenticationParameters(seconds, keystoreAuthTypes(req.method))
            } else {
                @Suppress("DEPRECATION")
                setUserAuthenticationValidityDurationSeconds(seconds)
            }
        }
        is UserAuthenticationRequirement.PerUse -> {
            setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setUserAuthenticationParameters(0, keystoreAuthTypes(req.method))
            } else {
                @Suppress("DEPRECATION")
                setUserAuthenticationValidityDurationSeconds(-1)
            }
        }
    }
}

/** [UserAuthenticationMethod] → the API 30+ keystore authenticator-class bitmask. */
private fun keystoreAuthTypes(method: UserAuthenticationMethod): Int =
    when (method) {
        UserAuthenticationMethod.BiometricOnly -> KeyProperties.AUTH_BIOMETRIC_STRONG
        UserAuthenticationMethod.BiometricOrCredential ->
            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
    }

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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cur.isTransientFailure) {
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

/** API 31+ only: `true` iff [privateKey]'s keystore-reported security level is StrongBox. */
private fun securityLevelIsStrongBox(privateKey: PrivateKey): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val info =
        KeyFactory
            .getInstance(privateKey.algorithm, ANDROID_KEY_STORE)
            .getKeySpec(privateKey, KeyInfo::class.java)
    return info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
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
 * The Android Keystore provider, resolved once. A device/emulator always has an `AndroidKeyStore`
 * (StrongBox vs TEE is reflected by [HardwareKeyProvider.dedicatedSecureElement], not by presence),
 * but a host-JVM unit-test run — which inherits the common test suite — has no `AndroidKeyStore`
 * provider, so construction fails closed to `null` there, keeping [CryptoCapabilities.hardware]
 * honestly [HardwareSupport.Unavailable] off-device.
 */
private val androidProvider: HardwareKeyProvider? by lazy {
    try {
        AndroidKeystoreHardwareKeyProvider()
    } catch (_: Throwable) {
        null
    }
}

internal actual fun platformHardwareKeyProvider(): HardwareKeyProvider? = androidProvider
