package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.crypto.CryptoTestVectors.hexBuffer

/** Helpers shared by the signature test suites. Inputs are built through the buffer API only. */
object SignatureTestSupport {
    /** Builds a [VerifyKey] for [scheme] from a hex-encoded public key (raw / uncompressed point). */
    fun verifyKey(
        scheme: SignatureScheme,
        publicKeyHex: String,
    ): VerifyKey {
        val pub = hexBuffer(publicKeyHex)
        return when (scheme) {
            SignatureScheme.Ed25519 -> VerifyKey.ed25519(pub)
            SignatureScheme.EcdsaP256 -> VerifyKey.ecdsaP256(pub)
            SignatureScheme.EcdsaP384 -> VerifyKey.ecdsaP384(pub)
            SignatureScheme.EcdsaP521 -> VerifyKey.ecdsaP521(pub)
        }
    }

    /** Builds a [SigningKey] for [scheme] from a hex-encoded private scalar / seed. */
    fun signingKey(
        scheme: SignatureScheme,
        privateHex: String,
    ): SigningKey {
        val priv = hexBuffer(privateHex)
        return when (scheme) {
            SignatureScheme.Ed25519 -> SigningKey.ed25519(priv)
            SignatureScheme.EcdsaP256 -> SigningKey.ecdsaP256(priv)
            SignatureScheme.EcdsaP384 -> SigningKey.ecdsaP384(priv)
            SignatureScheme.EcdsaP521 -> SigningKey.ecdsaP521(priv)
        }
    }

    /** Verifies a hex sig over a hex message under a hex public key via the async API. */
    suspend fun verifyHex(
        scheme: SignatureScheme,
        publicKeyHex: String,
        msgHex: String,
        sigHex: String,
    ): Boolean = verifyAsync(verifyKey(scheme, publicKeyHex), hexBuffer(msgHex), hexBuffer(sigHex))
}
