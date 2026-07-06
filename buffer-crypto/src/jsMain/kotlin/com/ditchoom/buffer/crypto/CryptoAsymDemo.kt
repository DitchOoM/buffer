@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.toReadBuffer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.Promise

/**
 * A thin, browser-facing facade over the real `buffer-crypto` asymmetric surface — key agreement
 * (X25519), HPKE, and signatures (Ed25519) — exported to JavaScript so the documentation site can
 * drive the actual library from an interactive widget.
 *
 * Like [CryptoDemo]'s AES-GCM surface, every call here routes through the real witness ops the
 * module ships (WebCrypto on the web), so the docs drive genuine bytes, not a JS re-implementation.
 * Key-agreement and HPKE private material never leaves a single call: the web key-agreement private
 * encoding is PKCS#8, not a raw scalar, so a whole exchange / seal-open round-trip runs inside one op
 * and only the *public* values (public keys, encapsulated key, ciphertext, shared secret) come back
 * as hex. Ed25519 keys round-trip as raw hex (seed + public key), so signing splits into stateless
 * generate/sign/verify calls the tamper demo drives independently.
 *
 * Multi-value results are returned as ':'-delimited hex, mirroring the library's own
 * `webCryptoGenerateKeyPair` "<priv>:<pub>" convention; the docs split them in TypeScript. Hex never
 * contains ':', so the split is unambiguous.
 *
 * This lives in its own `@JsExport` object (split out of [CryptoDemo]) purely to keep each facade's
 * function count under the complexity gate's limit — it is not a separate crypto implementation.
 */
@JsExport
@JsName("CryptoAsymDemo")
object CryptoAsymDemo {
    /** Bytes of shared key material the X25519 demo derives (HKDF output length). */
    private const val X25519_SHARED_BYTES = 32

    /** Domain-separation label for the key-agreement / HPKE demos (any fixed, non-secret context). */
    private const val DEMO_INFO = "buffer-crypto docs playground"

    /** A fresh domain-separation label buffer (fresh per call — the byte source is consumed on read). */
    private fun demoInfo(): Info = Info.Of(DEMO_INFO.toReadBuffer())

    /**
     * Runs a full X25519 (Curve25519 ECDH) exchange: two fresh key pairs agree on a shared secret,
     * each side deriving it from its own private key and the peer's *public* key. Resolves to
     * `pkAlice : pkBob : sharedFromAlice : sharedFromBob` (lowercase hex, ':'-delimited). The two
     * shared values are equal by the Diffie–Hellman contract — the docs show both to make the point
     * that the secret materialises identically at each end from public values that crossed the wire,
     * while Eve, who sees only the two public keys, cannot combine them into it.
     */
    fun x25519Exchange(): Promise<String> =
        GlobalScope.promise {
            val ops = keyAgreementAsyncOps(KeyAgreementCurve.X25519)
            val alice = ops.generateKeyPair()
            val bob = ops.generateKeyPair()
            try {
                val pkAlice = alice.publicKey.encoded.toHexRemaining()
                val pkBob = bob.publicKey.encoded.toHexRemaining()
                val sharedA = ops.deriveSharedSecret(alice.privateKey, bob.publicKey, demoInfo(), X25519_SHARED_BYTES)
                val sharedB = ops.deriveSharedSecret(bob.privateKey, alice.publicKey, demoInfo(), X25519_SHARED_BYTES)
                listOf(pkAlice, pkBob, sharedA.toHexRemaining(), sharedB.toHexRemaining()).joinToString(":")
            } finally {
                alice.close()
                bob.close()
            }
        }

    /**
     * Seals [plaintext] to a fresh HPKE recipient (Bob) with DHKEM(X25519)+HKDF-SHA256+AES-256-GCM,
     * then opens it two ways to show the asymmetry. Resolves to
     * `pkBob : enc : ciphertext : recovered : wrongKeyRejected` where:
     *  - `pkBob` is Bob's published public key (what Alice seals to),
     *  - `enc` is the encapsulated key and `ciphertext` the `ct‖tag` Eve taps off the wire,
     *  - `recovered` is the UTF-8 plaintext Bob recovers with his private key (hex-encoded, so a ':'
     *    in the message can't break the framing),
     *  - `wrongKeyRejected` is `1` if opening with a *different* private key (Eve's) failed, else `0`.
     */
    fun hpkeSealToRecipient(plaintext: String): Promise<String> =
        GlobalScope.promise {
            val suite = HpkeSuite(HpkeKem.DhkemX25519HkdfSha256, HpkeKdf.HkdfSha256, HpkeAead.Aes256Gcm)
            val ops =
                when (val w = CryptoCapabilities.hpke(suite)) {
                    is HpkeSupport.Supported -> w.ops
                    is HpkeSupport.Unsupported ->
                        throw UnsupportedOperationException("HPKE unavailable here: ${w.missing}")
                }
            val bob = hpkeGenerateKeyPair(suite.kem)
            val eve = hpkeGenerateKeyPair(suite.kem)
            try {
                val pkBob = bob.publicKeyBytes.toHexRemaining()
                val sealed = ops.sealBase(bob.publicKey, demoInfo(), plaintext.toReadBuffer())
                val encHex = sealed.enc.toHexRemaining()
                val ctHex = sealed.ciphertext.toHexRemaining()
                // Fresh buffers per open so positions never collide across the two decrypt attempts.
                val opened = ops.openBase(bob.privateKey, encHex.hexToReadBuffer(), demoInfo(), ctHex.hexToReadBuffer())
                val recovered = opened.readString(opened.remaining()).toReadBuffer().toHexRemaining()
                val wrongKeyRejected =
                    try {
                        ops.openBase(eve.privateKey, encHex.hexToReadBuffer(), demoInfo(), ctHex.hexToReadBuffer())
                        "0"
                    } catch (_: Throwable) {
                        "1"
                    }
                listOf(pkBob, encHex, ctHex, recovered, wrongKeyRejected).joinToString(":")
            } finally {
                bob.close()
                eve.close()
            }
        }

    /** The Ed25519 signature witness ops, or a clear throw if this engine lacks Ed25519. */
    private fun ed25519Ops(): SignatureAsyncOps =
        when (val w = CryptoCapabilities.signatures(SignatureScheme.Ed25519)) {
            is SignatureSupport.AsyncOnly -> w.ops
            is SignatureSupport.Blocking -> w.ops
            SignatureSupport.Unavailable ->
                throw UnsupportedOperationException("Ed25519 signatures are unavailable on this engine")
        }

    /**
     * A fresh Ed25519 signing key pair, resolved to `seed : publicKey` (lowercase hex): the 32-byte
     * RFC 8032 private seed (Alice's secret) and the 32-byte public key (what Bob verifies with).
     * Both round-trip back through [ed25519Sign] / [ed25519Verify].
     */
    fun ed25519GenerateKeypair(): Promise<String> =
        GlobalScope.promise {
            val sk = ed25519Ops().generateSigningKey()
            try {
                val seed = sk.requireInMemoryMaterial().toHexRemaining()
                val pub = sk.verifyKey.requireInMemoryMaterial().toHexRemaining()
                "$seed:$pub"
            } finally {
                sk.close()
            }
        }

    /**
     * Signs UTF-8 [message] under the Ed25519 [seedHex] (its matching [publicKeyHex] completes the
     * key), resolving to the 64-byte signature as hex. The signature proves Alice wrote exactly these
     * bytes; it does not hide the message.
     */
    fun ed25519Sign(
        seedHex: String,
        publicKeyHex: String,
        message: String,
    ): Promise<String> =
        GlobalScope.promise {
            val verifyKey = VerifyKey.ed25519(publicKeyHex.hexToReadBuffer())
            SigningKey.ed25519(seedHex.hexToReadBuffer(), verifyKey).use { sk ->
                ed25519Ops().sign(sk, message.toReadBuffer()).toHexRemaining()
            }
        }

    /**
     * Verifies that [signatureHex] is a valid Ed25519 signature of UTF-8 [message] under
     * [publicKeyHex]. Resolves to `true` only if it verifies; a tampered message, a flipped signature
     * byte, or the wrong key all resolve to `false` (never throw-as-valid) — the unforgeability the
     * signature scene demonstrates.
     */
    fun ed25519Verify(
        publicKeyHex: String,
        message: String,
        signatureHex: String,
    ): Promise<Boolean> =
        GlobalScope.promise {
            val verifyKey = VerifyKey.ed25519(publicKeyHex.hexToReadBuffer())
            ed25519Ops().verify(verifyKey, message.toReadBuffer(), signatureHex.hexToReadBuffer())
        }
}
