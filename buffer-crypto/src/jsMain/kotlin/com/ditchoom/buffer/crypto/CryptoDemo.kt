@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.toReadBuffer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.Promise

/**
 * Entry point for the JS executable bundle. The bundle exists only to expose [CryptoDemo] to the
 * documentation site, so there is nothing to run on load.
 */
fun main() = Unit

/**
 * A thin, browser-facing facade over the real `buffer-crypto` AES-GCM surface, exported to
 * JavaScript so the documentation site can drive the actual library from an interactive widget.
 *
 * This is **not** a separate crypto implementation: every call routes through the same witness ops
 * (`CryptoCapabilities.aesGcm`) the rest of the module uses, so what a reader plays with in the docs
 * is the shipping code path (WebCrypto AES-GCM on the web), not a mock.
 *
 * The boundary speaks lowercase hex strings rather than `ReadBuffer`/`PlatformBuffer`, because those
 * types are not `@JsExport`-able. Hex keeps the wire trivially inspectable in the browser console and
 * lets a reader *see* the `nonce ‖ ciphertext ‖ tag` framing.
 *
 * `suspend` cannot cross the `@JsExport` boundary, so the seal/open entry points return a
 * [Promise]; a failed tag verification rejects the promise (surfaced as [VerificationFailed]).
 */
@JsExport
@JsName("CryptoDemo")
object CryptoDemo {
    /** A fresh AES-256 key as 64 lowercase hex chars, drawn from the platform CSPRNG. */
    fun generateKeyHex(): String = cryptoRandom(AES_256_KEY_BYTES).toHexRemaining()

    /** A fresh 12-byte AES-GCM nonce as 24 lowercase hex chars. */
    fun generateNonceHex(): String = cryptoRandom(AEAD_NONCE_BYTES).toHexRemaining()

    /**
     * **Demo-only.** Seals with a *caller-supplied* nonce so the playground can pin the nonce while
     * you edit the plaintext and watch the ciphertext track it byte-for-byte. The shipping
     * [seal] never lets you supply a nonce on purpose: it draws a fresh one each call, because
     * reusing a `(key, nonce)` pair under AES-GCM is **catastrophic** (it leaks the authentication
     * key). Never imitate this in real code.
     */
    fun sealWithNonce(
        keyHex: String,
        nonceHex: String,
        plaintext: String,
        aad: String,
    ): Promise<String> =
        GlobalScope.promise {
            val key = AesGcmKey.of(keyHex.hexToReadBuffer())
            val sealed =
                aesGcmSealWithNonceAsync(
                    key = key,
                    nonce = nonceHex.hexToReadBuffer(),
                    aad = if (aad.isEmpty()) null else aad.toReadBuffer(),
                    plaintext = plaintext.toReadBuffer(),
                    factory = BufferFactory.Default,
                )
            // sealWithNonce returns ciphertext ‖ tag (no nonce prefix); prepend the nonce so the
            // output matches the self-framing layout the rest of the demo inspects.
            nonceHex + sealed.toHexRemaining()
        }

    /**
     * Seals UTF-8 [plaintext] under the hex AES-256 [keyHex], authenticating optional UTF-8 [aad].
     * Resolves to the self-framing `nonce ‖ ciphertext ‖ tag` buffer as hex (a fresh 12-byte nonce
     * is drawn per call, so the same input never produces the same output).
     */
    fun seal(
        keyHex: String,
        plaintext: String,
        aad: String,
    ): Promise<String> =
        GlobalScope.promise {
            val key = AesGcmKey.of(keyHex.hexToReadBuffer())
            val sealed = aesGcmOps().seal(key, plaintext.toReadBuffer(), aad.toAad())
            sealed.toHexRemaining()
        }

    /**
     * Opens a hex `nonce ‖ ciphertext ‖ tag` [sealedHex] under [keyHex] and [aad], resolving to the
     * recovered UTF-8 text. A tampered ciphertext/tag, the wrong key, or swapped AAD all reject the
     * promise with [VerificationFailed] — the plaintext is produced only after the tag verifies.
     */
    fun open(
        keyHex: String,
        sealedHex: String,
        aad: String,
    ): Promise<String> =
        GlobalScope.promise {
            val key = AesGcmKey.of(keyHex.hexToReadBuffer())
            val recovered = aesGcmOps().open(sealedHex.hexToReadBuffer(), key, aad.toAad())
            recovered.readString(recovered.remaining())
        }

    // =========================================================================
    // Asymmetric demo ops — key agreement (X25519), HPKE, and signatures (Ed25519).
    //
    // Like the AES-GCM surface above, every call routes through the real witness ops the module
    // ships (WebCrypto on the web), so the docs drive genuine bytes. Key-agreement and HPKE private
    // material never leaves a single call: the web key-agreement private encoding is PKCS#8, not a
    // raw scalar, so a whole exchange / seal-open round-trip runs inside one op and only the *public*
    // values (public keys, encapsulated key, ciphertext, shared secret) come back as hex. Ed25519
    // keys round-trip as raw hex (seed + public key), so signing splits into stateless
    // generate/sign/verify calls the tamper demo drives independently.
    //
    // Multi-value results are returned as ':'-delimited hex, mirroring the library's own
    // `webCryptoGenerateKeyPair` "<priv>:<pub>" convention; the docs split them in TypeScript. Hex
    // never contains ':', so the split is unambiguous.
    // =========================================================================

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
                    is HpkeSupport.Unsupported -> throw UnsupportedOperationException("HPKE unavailable here: ${w.missing}")
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
            SignatureSupport.Unavailable -> throw UnsupportedOperationException("Ed25519 signatures are unavailable on this engine")
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

    /**
     * What the AEAD witnesses resolve to in *this* engine — e.g. `AES-GCM=AsyncOnly;
     * ChaCha20-Poly1305=Unavailable` on the web. Lets the docs show the capability-witness model is
     * a real, observable per-platform fact rather than prose.
     */
    fun capabilities(): String {
        val gcm =
            when (CryptoCapabilities.aesGcm) {
                is Aead.AsyncOnly -> "AsyncOnly"
                is Aead.Blocking -> "Blocking"
            }
        val chaCha =
            when (CryptoCapabilities.chaChaPoly) {
                is OptionalAead.AsyncOnly -> "AsyncOnly"
                is OptionalAead.Blocking -> "Blocking"
                OptionalAead.Unavailable -> "Unavailable"
            }
        return "AES-GCM=$gcm; ChaCha20-Poly1305=$chaCha"
    }

    /** The AES-GCM async ops, resolved once from the witness (always `AsyncOnly` on the web). */
    private fun aesGcmOps(): AeadAsyncOps<AesGcmKey> =
        when (val witness = CryptoCapabilities.aesGcm) {
            is Aead.AsyncOnly -> witness.ops
            is Aead.Blocking -> witness.ops
        }

    private fun String.toAad(): Aad = if (isEmpty()) Aad.None else Aad.Of(toReadBuffer())

    private fun String.hexToReadBuffer(): ReadBuffer {
        val out = BufferFactory.Default.allocate(length / 2)
        out.writeHex(this)
        out.resetForRead()
        return out
    }
}
