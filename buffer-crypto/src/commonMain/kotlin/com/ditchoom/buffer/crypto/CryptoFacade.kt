package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.crypto.hpkeGenerateKeyPair as freeHpkeGenerateKeyPair
import com.ditchoom.buffer.crypto.hpkeImportPrivateKey as freeHpkeImportPrivateKey
import com.ditchoom.buffer.crypto.hpkeImportPublicKey as freeHpkeImportPublicKey
import com.ditchoom.buffer.crypto.importPrivateKey as freeImportPrivateKey

/*
 * Namespaced entry points for the crypto primitive families.
 *
 * Each object below groups a family's key-construction free functions under a single discoverable
 * name. The cryptographic *operations* for every family live on a capability witness, not here:
 * AEAD on [Aead] / [OptionalAead] ([CryptoCapabilities.aesGcm] / [CryptoCapabilities.chaChaPoly]),
 * signatures on [SignatureSupport] ([CryptoCapabilities.signatures]), key agreement on
 * [KeyAgreementSupport] ([CryptoCapabilities.keyAgreement]), and HPKE on [HpkeSupport]
 * ([CryptoCapabilities.hpke]). These facades carry only the platform-independent key import /
 * generation, which never "lacks" on a platform and so does not belong on a witness.
 */

/**
 * Key-exchange / agreement (X25519, ECDH P-256/P-384/P-521) namespaced entry point. The
 * *operations* (`generateKeyPair` / `deriveSharedSecret`) live on the [KeyAgreementSupport] witness
 * reached via [CryptoCapabilities.keyAgreement]; public keys are constructed via
 * [KeyAgreementPublicKey.of]. Only the platform-independent private-key import remains here.
 */
object Kex {
    /** @see importPrivateKey */
    fun importPrivateKey(
        curve: KeyAgreementCurve,
        encoded: ReadBuffer,
    ): KeyAgreementPrivateKey = freeImportPrivateKey(curve, encoded)
}

/**
 * HPKE (RFC 9180) key-construction namespaced entry points: key-pair generation and recipient/sender
 * key import. The seal/open and Base/PSK/Auth/AuthPSK setup *operations* live on the [HpkeOps] that
 * [CryptoCapabilities.hpke] exposes through its [HpkeSupport.Supported] witness.
 */
object Hpke {
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
}
