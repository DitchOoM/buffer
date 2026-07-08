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
 * Entry point for the JS executable bundle. The bundle exists only to expose [CryptoDemo] and
 * [CryptoAsymDemo] to the documentation site, so there is nothing to run on load.
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
 *
 * The asymmetric ops (key agreement, HPKE, signatures) live in the sibling [CryptoAsymDemo] facade
 * so each `@JsExport` object stays under the complexity gate's function-count limit.
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
    private fun aesGcmOps(): AeadAsyncOps<AesGcmKey, SyncCapableAesGcmKey> =
        when (val witness = CryptoCapabilities.aesGcm) {
            is Aead.AsyncOnly -> witness.ops
            is Aead.Blocking -> witness.ops
        }

    private fun String.toAad(): Aad = if (isEmpty()) Aad.None else Aad.Of(toReadBuffer())
}

/**
 * Parses a lowercase hex string into a fresh [ReadBuffer]. Shared between [CryptoDemo] and
 * [CryptoAsymDemo] as a top-level (non-member) helper so it doesn't count against either
 * `@JsExport` object's function budget.
 */
internal fun String.hexToReadBuffer(): ReadBuffer {
    val out = BufferFactory.Default.allocate(length / 2)
    out.writeHex(this)
    out.resetForRead()
    return out
}
