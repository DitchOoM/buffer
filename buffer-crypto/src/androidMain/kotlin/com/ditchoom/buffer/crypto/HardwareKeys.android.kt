package com.ditchoom.buffer.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
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
 * never in process memory. Only the 12-byte GCM nonce and the JDK-required DER signature bytes cross
 * the JCA boundary as arrays — the payload itself streams buffer→cipher→buffer through the shared
 * [finalInto] / [openInto] plumbing, identical to the in-memory AES-GCM path.
 *
 * Eligibility mirrors what a secure element actually backs: AES-256/128-GCM and ECDSA P-256.
 * (Ed25519 / P-384 / P-521 are not offered — see [eligible].)
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
     * `true` once a `setIsStrongBoxBacked(true)` generation succeeds — i.e. a dedicated StrongBox
     * secure element is present. Probed once (generate-and-delete a throwaway key) because the SPI
     * carries no `Context` with which to query `FEATURE_STRONGBOX_KEYSTORE`.
     */
    override val dedicatedSecureElement: Boolean by lazy { probeStrongBox() }

    override fun eligible(alg: HardwareAlgorithm): Boolean = alg == HardwareAlgorithm.AesGcm || alg == HardwareAlgorithm.EcdsaP256

    override suspend fun generateAesGcm(spec: HardwareKeySpec): AesGcmKey {
        require(spec.aesKeySizeBits == AES_128_KEY_BYTES * Byte.SIZE_BITS || spec.aesKeySizeBits == AES_256_KEY_BYTES * Byte.SIZE_BITS) {
            "AES key size must be 128 or 256 bits, was ${spec.aesKeySizeBits}"
        }
        val alias = newAlias("aes")
        val key = generateAesKey(alias, spec.aesKeySizeBits)
        val auth = spec.authorization
        return HardwareAesGcmKey(
            sizeBits = spec.aesKeySizeBits,
            gatedSeal = { nonce, aad, plaintext, factory ->
                if (!auth.authorize()) throw AuthorizationFailed()
                requireNonce(nonce)
                val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(gcmTagBits, nonceBytes(nonce)))
                aad?.let { cipher.updateAADRemaining(it) }
                val out = factory.allocate(plaintext.remaining() + AEAD_TAG_BYTES)
                finalInto(cipher, plaintext, out)
                out.resetForRead()
                out
            },
            gatedOpen = { nonce, aad, ciphertextAndTag, factory ->
                if (!auth.authorize()) throw AuthorizationFailed()
                requireNonce(nonce)
                requireTagged(ciphertextAndTag)
                val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(gcmTagBits, nonceBytes(nonce)))
                aad?.let { cipher.updateAADRemaining(it) }
                val out = factory.allocate(maxOf(0, ciphertextAndTag.remaining() - AEAD_TAG_BYTES))
                openInto(cipher, ciphertextAndTag, out)
                out.resetForRead()
                out
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
        val keyPair = generateEcKeyPair(alias)
        val privateKey = keyPair.private
        val verifyKey = VerifyKey.ecdsaP256(uncompressedPoint(keyPair.public as ECPublicKey))
        val auth = spec.authorization
        return HardwareSigningKey(
            scheme = SignatureScheme.EcdsaP256,
            gatedSign = { message, factory ->
                if (!auth.authorize()) throw AuthorizationFailed()
                val signer = Signature.getInstance(ECDSA_SHA256)
                signer.initSign(privateKey)
                signer.update(message.remainingBytes())
                val der = signer.sign()
                val out: PlatformBuffer = factory.allocate(der.size)
                out.writeBytes(der)
                out.resetForRead()
                out
            },
            verifyKey = verifyKey,
            onClose = { runCatching { keyStore.deleteEntry(alias) } },
        )
    }

    // --- keystore generation ---------------------------------------------------------------------

    private fun generateAesKey(
        alias: String,
        sizeBits: Int,
    ): SecretKey {
        val build = { strongBox: Boolean ->
            KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
                .apply {
                    init(aesSpec(alias, sizeBits, strongBox))
                }.generateKey()
        }
        return try {
            build(true)
        } catch (_: StrongBoxUnavailableException) {
            build(false)
        }
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
            // The witness generates a fresh CSPRNG nonce per seal (see HardwareAesGcmKey.sealFramed)
            // and supplies it explicitly, so the keystore must accept a caller IV rather than picking
            // its own. Uniqueness is the library's responsibility (a fresh nonce on every seal).
            .setRandomizedEncryptionRequired(false)
            .setIsStrongBoxBacked(strongBox)
            .build()

    private fun generateEcKeyPair(alias: String): java.security.KeyPair {
        val build = { strongBox: Boolean ->
            KeyPairGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
                .apply {
                    initialize(ecSpec(alias, strongBox))
                }.generateKeyPair()
        }
        return try {
            build(true)
        } catch (_: StrongBoxUnavailableException) {
            build(false)
        }
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

    private fun probeStrongBox(): Boolean {
        val alias = newAlias("probe")
        return try {
            KeyPairGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
                .apply {
                    initialize(ecSpec(alias, strongBox = true))
                }.generateKeyPair()
            true
        } catch (_: Throwable) {
            // StrongBoxUnavailableException (no secure element) or any provisioning failure ⇒ not a
            // dedicated secure element. AES-GCM / ECDSA still work TEE-backed.
            false
        } finally {
            runCatching { keyStore.deleteEntry(alias) }
        }
    }

    private fun newAlias(kind: String): String = "$ALIAS_PREFIX$kind-${UUID.randomUUID()}"

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

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"
        const val ECDSA_SHA256 = "SHA256withECDSA"
        const val SECP256R1 = "secp256r1"
        const val ALIAS_PREFIX = "com.ditchoom.buffer.crypto.hw."
        const val P256_FIELD_BYTES = 32
        const val SEC1_UNCOMPRESSED = 0x04
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
