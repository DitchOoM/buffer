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
 * The per-use gate is [HardwareKeySpec.authorization], evaluated in Kotlin before every op. OS-level
 * user-authentication binding (`setUserAuthenticationRequired`) is intentionally NOT set: it would
 * make a key unusable without a fresh device unlock / biometric, which the [HardwareAuthorization]
 * gate already models at the SPI level and which would break unattended conformance runs. Binding the
 * keystore to a biometric prompt is a future enhancement layered on top of the gate.
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
        require(keyBits == AES_128_KEY_BYTES * Byte.SIZE_BITS || keyBits == AES_256_KEY_BYTES * Byte.SIZE_BITS) {
            "AES key size must be 128 or 256 bits, was $keyBits"
        }
        val alias = newAlias("aes")
        val key = keystoreOp { generateAesKey(alias, spec.aesKeySizeBits) }
        val auth = spec.authorization
        return HardwareAesGcmKey(
            sizeBits = spec.aesKeySizeBits,
            gatedSeal = { aad, plaintext, factory ->
                if (!auth.authorize()) throw AuthorizationFailed()
                keystoreOp {
                    val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
                    // Randomized-encryption-required (the JCA default) ⇒ the keystore generates a
                    // fresh 12-byte GCM IV at init; read it back and frame it ahead of the ciphertext.
                    cipher.init(Cipher.ENCRYPT_MODE, key)
                    val nonce = cipher.iv
                    aad?.let { cipher.updateAADRemaining(it) }
                    val out = factory.allocate(nonce.size + plaintext.remaining() + AEAD_TAG_BYTES)
                    out.writeBytes(nonce)
                    finalInto(cipher, plaintext, out)
                    out.resetForRead()
                    out
                }
            },
            gatedOpen = { nonce, aad, ciphertextAndTag, factory ->
                if (!auth.authorize()) throw AuthorizationFailed()
                requireNonce(nonce)
                requireTagged(ciphertextAndTag)
                keystoreOp {
                    val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(gcmTagBits, nonceBytes(nonce)))
                    aad?.let { cipher.updateAADRemaining(it) }
                    val out = factory.allocate(maxOf(0, ciphertextAndTag.remaining() - AEAD_TAG_BYTES))
                    openInto(cipher, ciphertextAndTag, out)
                    out.resetForRead()
                    out
                }
            },
            onClose = { runCatching { keyStore.deleteEntry(alias) } },
        )
    }

    override suspend fun generateSigning(
        scheme: SignatureScheme,
        spec: HardwareKeySpec,
    ): SigningKey {
        require(eligible(scheme.toHardwareAlgorithm())) {
            "${scheme.schemeName} is not eligible for hardware backing"
        }
        val alias = newAlias("ec")
        val keyPair = keystoreOp { generateEcKeyPair(alias) }
        val privateKey = keyPair.private
        val verifyKey = VerifyKey.ecdsaP256(uncompressedPoint(keyPair.public as ECPublicKey))
        val auth = spec.authorization
        return HardwareSigningKey(
            scheme = SignatureScheme.EcdsaP256,
            gatedSign = { message, factory ->
                if (!auth.authorize()) throw AuthorizationFailed()
                keystoreOp {
                    val signer = Signature.getInstance(ECDSA_SHA256)
                    signer.initSign(privateKey)
                    signer.update(message.remainingBytes())
                    val der = signer.sign()
                    val out: PlatformBuffer = factory.allocate(der.size)
                    out.writeBytes(der)
                    out.resetForRead()
                    out
                }
            },
            verifyKey = verifyKey,
            onClose = { runCatching { keyStore.deleteEntry(alias) } },
        )
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
    ): SecretKey =
        generateWithStrongBoxFallback { strongBox ->
            KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
                .apply {
                    init(aesSpec(alias, sizeBits, strongBox))
                }.generateKey()
        }

    private fun aesSpec(
        alias: String,
        sizeBits: Int,
        strongBox: Boolean,
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
            .build()

    private fun generateEcKeyPair(alias: String): java.security.KeyPair =
        generateWithStrongBoxFallback { strongBox ->
            KeyPairGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
                .apply {
                    initialize(ecSpec(alias, strongBox))
                }.generateKeyPair()
        }

    private fun ecSpec(
        alias: String,
        strongBox: Boolean,
    ): KeyGenParameterSpec =
        KeyGenParameterSpec
            .Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec(SECP256R1))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
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
                        .apply { initialize(ecSpec(alias, strongBox = true)) }
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

    private fun newAlias(kind: String): String = "$ALIAS_PREFIX$kind-${UUID.randomUUID()}"
}

private const val ANDROID_KEY_STORE = "AndroidKeyStore"
private const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"
private const val ECDSA_SHA256 = "SHA256withECDSA"
private const val SECP256R1 = "secp256r1"
private const val ALIAS_PREFIX = "com.ditchoom.buffer.crypto.hw."
private const val P256_FIELD_BYTES = 32
private const val SEC1_UNCOMPRESSED = 0x04

// --- stateless keystore helpers (file-level: no provider state, kept off the class) --------------

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
