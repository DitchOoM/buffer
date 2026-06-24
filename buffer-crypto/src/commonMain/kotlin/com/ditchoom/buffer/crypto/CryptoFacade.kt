package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.crypto.deriveSharedSecret as freeDeriveSharedSecret
import com.ditchoom.buffer.crypto.deriveSharedSecretAsync as freeDeriveSharedSecretAsync
import com.ditchoom.buffer.crypto.ed25519AsyncAvailable as freeEd25519AsyncAvailable
import com.ditchoom.buffer.crypto.generateKeyPair as freeGenerateKeyPair
import com.ditchoom.buffer.crypto.generateKeyPairAsync as freeGenerateKeyPairAsync
import com.ditchoom.buffer.crypto.hpkeGenerateKeyPair as freeHpkeGenerateKeyPair
import com.ditchoom.buffer.crypto.hpkeImportPrivateKey as freeHpkeImportPrivateKey
import com.ditchoom.buffer.crypto.hpkeImportPublicKey as freeHpkeImportPublicKey
import com.ditchoom.buffer.crypto.hpkeOpenBase as freeHpkeOpenBase
import com.ditchoom.buffer.crypto.hpkeSealBase as freeHpkeSealBase
import com.ditchoom.buffer.crypto.hpkeSetupAuthPskReceiver as freeHpkeSetupAuthPskReceiver
import com.ditchoom.buffer.crypto.hpkeSetupAuthPskSender as freeHpkeSetupAuthPskSender
import com.ditchoom.buffer.crypto.hpkeSetupAuthReceiver as freeHpkeSetupAuthReceiver
import com.ditchoom.buffer.crypto.hpkeSetupAuthSender as freeHpkeSetupAuthSender
import com.ditchoom.buffer.crypto.hpkeSetupBaseReceiver as freeHpkeSetupBaseReceiver
import com.ditchoom.buffer.crypto.hpkeSetupBaseSender as freeHpkeSetupBaseSender
import com.ditchoom.buffer.crypto.hpkeSetupPskReceiver as freeHpkeSetupPskReceiver
import com.ditchoom.buffer.crypto.hpkeSetupPskSender as freeHpkeSetupPskSender
import com.ditchoom.buffer.crypto.hpkeSupported as freeHpkeSupported
import com.ditchoom.buffer.crypto.importPrivateKey as freeImportPrivateKey
import com.ditchoom.buffer.crypto.maxSignatureBytes as freeMaxSignatureBytes
import com.ditchoom.buffer.crypto.sign as freeSign
import com.ditchoom.buffer.crypto.signAsync as freeSignAsync
import com.ditchoom.buffer.crypto.signInto as freeSignInto
import com.ditchoom.buffer.crypto.supportsSync as freeSupportsSync
import com.ditchoom.buffer.crypto.verify as freeVerify
import com.ditchoom.buffer.crypto.verifyAsync as freeVerifyAsync

/*
 * Namespaced entry points for the crypto primitive families.
 *
 * Each object below groups the family's free functions under a single discoverable name so IDE
 * completion surfaces, e.g., `Sign.sign` / `Kex.deriveSharedSecret` / `Hpke.sealBase` instead of a
 * flat list of top-level functions. (AEAD has no facade object — its operations live on the
 * [Aead] / [OptionalAead] capability witnesses.) These are thin, additive
 * facades: every member delegates to the existing top-level function of the same family (which
 * remains the canonical, unchanged public API — many are `expect`/`internal`-backed and cannot
 * move into an object). Nothing here is breaking; the original top-level functions are kept
 * exactly as-is, so existing callers continue to compile unchanged.
 */

/**
 * Digital-signature (Ed25519, ECDSA P-256/P-384/P-521) namespaced entry points. Delegates to the
 * top-level `sign*` / `verify*` functions.
 */
object Sign {
    /** @see signInto */
    fun signInto(
        key: SigningKey,
        message: ReadBuffer,
        dest: WriteBuffer,
    ): Int = freeSignInto(key, message, dest)

    /** @see verify */
    fun verify(
        key: VerifyKey,
        message: ReadBuffer,
        signature: ReadBuffer,
    ): Boolean = freeVerify(key, message, signature)

    /** @see maxSignatureBytes */
    fun maxSignatureBytes(scheme: SignatureScheme): Int = freeMaxSignatureBytes(scheme)

    /** @see sign */
    fun sign(
        key: SigningKey,
        message: ReadBuffer,
        factory: BufferFactory = BufferFactory.Default,
    ): ReadBuffer = freeSign(key, message, factory)

    /** @see signAsync */
    suspend fun signAsync(
        key: SigningKey,
        message: ReadBuffer,
        factory: BufferFactory = BufferFactory.Default,
    ): ReadBuffer = freeSignAsync(key, message, factory)

    /** @see verifyAsync */
    suspend fun verifyAsync(
        key: VerifyKey,
        message: ReadBuffer,
        signature: ReadBuffer,
    ): Boolean = freeVerifyAsync(key, message, signature)

    /** @see ed25519AsyncAvailable */
    suspend fun ed25519AsyncAvailable(): Boolean = freeEd25519AsyncAvailable()
}

/**
 * Key-exchange / agreement (X25519, ECDH P-256/P-384/P-521) namespaced entry points. Delegates to
 * the top-level `generateKeyPair*` / `importPrivateKey` / `deriveSharedSecret*` functions.
 */
object Kex {
    /** @see generateKeyPair */
    fun generateKeyPair(curve: KeyAgreementCurve): KeyAgreementKeyPair = freeGenerateKeyPair(curve)

    /** @see importPrivateKey */
    fun importPrivateKey(
        curve: KeyAgreementCurve,
        encoded: ReadBuffer,
    ): KeyAgreementPrivateKey = freeImportPrivateKey(curve, encoded)

    /** @see deriveSharedSecret */
    fun deriveSharedSecret(
        privateKey: KeyAgreementPrivateKey,
        peerPublicKey: KeyAgreementPublicKey,
        info: ReadBuffer,
        length: Int,
        salt: ReadBuffer? = null,
        factory: BufferFactory = BufferFactory.Default,
    ): ReadBuffer = freeDeriveSharedSecret(privateKey, peerPublicKey, info, length, salt, factory)

    /** @see supportsSync */
    fun supportsSync(curve: KeyAgreementCurve): Boolean = freeSupportsSync(curve)

    /** @see generateKeyPairAsync */
    suspend fun generateKeyPairAsync(curve: KeyAgreementCurve): KeyAgreementKeyPair = freeGenerateKeyPairAsync(curve)

    /** @see deriveSharedSecretAsync */
    suspend fun deriveSharedSecretAsync(
        privateKey: KeyAgreementPrivateKey,
        peerPublicKey: KeyAgreementPublicKey,
        info: ReadBuffer,
        length: Int,
        salt: ReadBuffer? = null,
        factory: BufferFactory = BufferFactory.Default,
    ): ReadBuffer = freeDeriveSharedSecretAsync(privateKey, peerPublicKey, info, length, salt, factory)
}

/**
 * HPKE (RFC 9180) namespaced entry points: key handling, single-shot seal/open, and the
 * Base/PSK/Auth/AuthPSK setup variants. Delegates to the top-level `hpke*` functions.
 *
 * The four HPKE setup modes (Base/PSK/Auth/AuthPSK) each need key handling plus seal/open, so this
 * cohesive namespace object necessarily exposes more than the default per-object function cap.
 */
@Suppress("TooManyFunctions")
object Hpke {
    /** @see hpkeSupported */
    fun supported(suite: HpkeSuite): Boolean = freeHpkeSupported(suite)

    /** @see hpkeGenerateKeyPair */
    suspend fun generateKeyPair(kem: HpkeKem): HpkeKeyPair = freeHpkeGenerateKeyPair(kem)

    /** @see hpkeImportPublicKey */
    fun importPublicKey(
        kem: HpkeKem,
        encoded: ReadBuffer,
    ): HpkePublicKey = freeHpkeImportPublicKey(kem, encoded)

    /** @see hpkeImportPrivateKey */
    fun importPrivateKey(
        kem: HpkeKem,
        privateEncoded: ReadBuffer,
        publicEncoded: ReadBuffer,
    ): HpkePrivateKey = freeHpkeImportPrivateKey(kem, privateEncoded, publicEncoded)

    /** @see hpkeSealBase */
    suspend fun sealBase(
        suite: HpkeSuite,
        recipientPublicKey: HpkePublicKey,
        info: ReadBuffer,
        plaintext: ReadBuffer,
        aad: ReadBuffer? = null,
        factory: BufferFactory = BufferFactory.Default,
    ): HpkeSealed = freeHpkeSealBase(suite, recipientPublicKey, info, plaintext, aad, factory)

    /** @see hpkeOpenBase */
    suspend fun openBase(
        suite: HpkeSuite,
        recipientPrivateKey: HpkePrivateKey,
        enc: ReadBuffer,
        info: ReadBuffer,
        ciphertext: ReadBuffer,
        aad: ReadBuffer? = null,
        factory: BufferFactory = BufferFactory.Default,
    ): PlatformBuffer = freeHpkeOpenBase(suite, recipientPrivateKey, enc, info, ciphertext, aad, factory)

    /** @see hpkeSetupBaseSender */
    suspend fun setupBaseSender(
        suite: HpkeSuite,
        recipientPublicKey: HpkePublicKey,
        info: ReadBuffer,
    ): HpkeSenderSetup = freeHpkeSetupBaseSender(suite, recipientPublicKey, info)

    /** @see hpkeSetupPskSender */
    suspend fun setupPskSender(
        suite: HpkeSuite,
        recipientPublicKey: HpkePublicKey,
        info: ReadBuffer,
        psk: HpkePsk,
    ): HpkeSenderSetup = freeHpkeSetupPskSender(suite, recipientPublicKey, info, psk)

    /** @see hpkeSetupAuthSender */
    suspend fun setupAuthSender(
        suite: HpkeSuite,
        recipientPublicKey: HpkePublicKey,
        info: ReadBuffer,
        senderPrivateKey: HpkePrivateKey,
    ): HpkeSenderSetup = freeHpkeSetupAuthSender(suite, recipientPublicKey, info, senderPrivateKey)

    /** @see hpkeSetupAuthPskSender */
    suspend fun setupAuthPskSender(
        suite: HpkeSuite,
        recipientPublicKey: HpkePublicKey,
        info: ReadBuffer,
        psk: HpkePsk,
        senderPrivateKey: HpkePrivateKey,
    ): HpkeSenderSetup = freeHpkeSetupAuthPskSender(suite, recipientPublicKey, info, psk, senderPrivateKey)

    /** @see hpkeSetupBaseReceiver */
    suspend fun setupBaseReceiver(
        suite: HpkeSuite,
        recipientPrivateKey: HpkePrivateKey,
        enc: ReadBuffer,
        info: ReadBuffer,
    ): HpkeContext.Receiver = freeHpkeSetupBaseReceiver(suite, recipientPrivateKey, enc, info)

    /** @see hpkeSetupPskReceiver */
    suspend fun setupPskReceiver(
        suite: HpkeSuite,
        recipientPrivateKey: HpkePrivateKey,
        enc: ReadBuffer,
        info: ReadBuffer,
        psk: HpkePsk,
    ): HpkeContext.Receiver = freeHpkeSetupPskReceiver(suite, recipientPrivateKey, enc, info, psk)

    /** @see hpkeSetupAuthReceiver */
    suspend fun setupAuthReceiver(
        suite: HpkeSuite,
        recipientPrivateKey: HpkePrivateKey,
        enc: ReadBuffer,
        info: ReadBuffer,
        senderPublicKey: HpkePublicKey,
    ): HpkeContext.Receiver = freeHpkeSetupAuthReceiver(suite, recipientPrivateKey, enc, info, senderPublicKey)

    /** @see hpkeSetupAuthPskReceiver */
    suspend fun setupAuthPskReceiver(
        suite: HpkeSuite,
        recipientPrivateKey: HpkePrivateKey,
        enc: ReadBuffer,
        info: ReadBuffer,
        psk: HpkePsk,
        senderPublicKey: HpkePublicKey,
    ): HpkeContext.Receiver = freeHpkeSetupAuthPskReceiver(suite, recipientPrivateKey, enc, info, psk, senderPublicKey)
}
