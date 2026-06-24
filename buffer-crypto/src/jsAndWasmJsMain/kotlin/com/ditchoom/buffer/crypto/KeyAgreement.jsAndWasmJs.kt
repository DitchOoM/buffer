package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer

/**
 * js/wasmJs key agreement over **WebCrypto** (`crypto.subtle`). WebCrypto's key-agreement API is
 * entirely Promise-based, so there is **no synchronous** path on the web: every `supportsSync…`
 * flag is `false` and the synchronous [generateKeyPair]/[deriveSharedSecret] throw
 * [UnsupportedOperationException]. Use the `…Async` variants, which await the WebCrypto Promises.
 *
 * X25519 in WebCrypto landed only in newer engines (Chrome 137+, Firefox 129+, Safari 17+, Node
 * stable). It is **feature-detected** at runtime via [webCryptoSupportsX25519]; if the engine lacks
 * it the async X25519 entry points throw [UnsupportedOperationException] (the documented error path
 * for an absent algorithm) rather than silently degrading. ECDH P-256/384/521 is broadly available.
 *
 * Encoding matches the pinned cross-platform contract: WebCrypto `raw` export/import is the raw
 * 32-byte X25519 u-coordinate and the uncompressed `0x04 ‖ X ‖ Y` EC point — the same bytes the
 * JVM and Apple glue produce. Off-curve / low-order peer points are rejected by `importKey` /
 * `deriveBits`, surfaced as [InvalidPublicKey].
 */

private const val HEX_RADIX = 16
private const val NIBBLE_BITS = 4

/** Web key agreement is async-only (WebCrypto is Promise-based); every curve resolves to AsyncOnly. */
actual fun CryptoCapabilities.keyAgreement(curve: KeyAgreementCurve): KeyAgreementSupport =
    KeyAgreementSupport.AsyncOnly(KeyAgreementAsyncOpsImpl(curve))

private fun unsupportedSync(curve: KeyAgreementCurve): Nothing =
    throw UnsupportedOperationException(
        "${curve.curveName} key agreement is async-only on this platform (WebCrypto). Use the Async variant.",
    )

// The web witness is AsyncOnly, so the synchronous primitives are never reached; they throw as a
// fail-fast safety net (mirroring the Signatures web bridge).
internal actual fun generateKeyPairPlatform(curve: KeyAgreementCurve): KeyAgreementKeyPair = unsupportedSync(curve)

internal actual fun deriveSharedSecretPlatform(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
    info: ReadBuffer?,
    length: Int,
    salt: ReadBuffer?,
    factory: BufferFactory,
): ReadBuffer = unsupportedSync(privateKey.curve)

/** WebCrypto algorithm descriptor for a curve, e.g. `"X25519"` or named-curve EC. */
private fun ensureSupported(curve: KeyAgreementCurve) {
    if (curve == KeyAgreementCurve.X25519 && !webCryptoSupportsX25519) {
        throw UnsupportedOperationException("X25519 is not available in this WebCrypto engine")
    }
}

/** Lowercase hex of a buffer's remaining bytes (non-destructive); for WebCrypto string marshalling. */
private fun ReadBuffer.toHex(): String {
    val digits = "0123456789abcdef"
    val start = position()
    val n = remaining()
    val sb = StringBuilder(n * 2)
    for (i in 0 until n) {
        val v = get(start + i).toInt() and 0xFF
        sb.append(digits[v ushr NIBBLE_BITS])
        sb.append(digits[v and 0xF])
    }
    return sb.toString()
}

/** Writes a hex string's bytes into a fresh read-ready buffer from [factory]. */
private fun hexToBuffer(
    hex: String,
    factory: BufferFactory,
): PlatformBuffer {
    val n = hex.length / 2
    val b = factory.allocate(n)
    for (i in 0 until n) b.writeByte(hex.substring(i * 2, i * 2 + 2).toInt(HEX_RADIX).toByte())
    b.resetForRead()
    return b
}

internal actual suspend fun generateKeyPairAsyncPlatform(curve: KeyAgreementCurve): KeyAgreementKeyPair {
    ensureSupported(curve)
    // WebCrypto returns "publicHex|privateHex" (raw public export, PKCS#8 private export), or the
    // sentinel "UNSUPPORTED" if the engine lacks the algorithm (e.g. X25519 on an older browser).
    val result = webCryptoGenerateKeyPair(curve.curveName)
    if (result.publicHex == UNSUPPORTED_SENTINEL) {
        throw UnsupportedOperationException("${curve.curveName} is not available in this WebCrypto engine")
    }
    val pubBuf = hexToBuffer(result.publicHex, BufferFactory.Default)
    val privBuf = hexToBuffer(result.privateHex, secureScratch)
    val publicKey = KeyAgreementPublicKey.of(curve, pubBuf)
    return keyAgreementKeyPairOf(curve, keyAgreementPrivateKeyOf(curve, privBuf), publicKey)
}

// A failed WebCrypto import/deriveBits is mapped to a single uniform InvalidPublicKey: Throwable is
// caught and its cause intentionally dropped so a malformed/off-curve peer point cannot be told apart
// by the engine's exception type (oracle avoidance).
@Suppress("SwallowedException", "TooGenericExceptionCaught")
internal actual suspend fun deriveSharedSecretAsyncPlatform(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
    info: ReadBuffer?,
    length: Int,
    salt: ReadBuffer?,
    factory: BufferFactory,
): ReadBuffer {
    val curve = privateKey.curve
    require(curve == peerPublicKey.curve) { "private/public key curve mismatch" }
    ensureSupported(curve)

    val sharedHex =
        try {
            webCryptoDeriveSharedSecret(
                curve.curveName,
                privateKey.requireInMemoryMaterial().toHex(),
                peerPublicKey.encoded.toHex(),
            )
        } catch (e: Throwable) {
            // A failed import / deriveBits means an off-curve / low-order / malformed peer point.
            throw InvalidPublicKey(curve.curveName)
        }
    if (sharedHex == UNSUPPORTED_SENTINEL) {
        throw UnsupportedOperationException("${curve.curveName} is not available in this WebCrypto engine")
    }
    val raw = hexToBuffer(sharedHex, secureScratch)
    return deriveFromRawSecret(curve, raw, info, length, salt, factory)
}

/**
 * Raw DH secret seam for HPKE/DHKEM on the web. Awaits WebCrypto `deriveBits` and returns the raw
 * secret in a wiped SecureBuffer (no KDF). Same validation contract as [deriveSharedSecretAsync].
 */
@Suppress("SwallowedException", "TooGenericExceptionCaught")
internal actual suspend fun dhRawSecret(
    privateKey: KeyAgreementPrivateKey,
    peerPublicKey: KeyAgreementPublicKey,
): PlatformBuffer {
    val curve = privateKey.curve
    require(curve == peerPublicKey.curve) { "private/public key curve mismatch" }
    ensureSupported(curve)

    val sharedHex =
        try {
            webCryptoDeriveSharedSecret(
                curve.curveName,
                privateKey.requireInMemoryMaterial().toHex(),
                peerPublicKey.encoded.toHex(),
            )
        } catch (e: Throwable) {
            throw InvalidPublicKey(curve.curveName)
        }
    if (sharedHex == UNSUPPORTED_SENTINEL) {
        throw UnsupportedOperationException("${curve.curveName} is not available in this WebCrypto engine")
    }
    val raw = hexToBuffer(sharedHex, secureScratch)
    return validateRawSecret(curve, raw)
}

/** Sentinel the JS/wasm glue returns when WebCrypto lacks the requested algorithm (e.g. X25519). */
internal const val UNSUPPORTED_SENTINEL: String = "UNSUPPORTED"

/** Result of a WebCrypto key-pair generation, marshalled as hex (raw public, raw/PKCS#8 private). */
internal class WebCryptoKeyPair(
    val publicHex: String,
    val privateHex: String,
)

/** Runtime feature-detect: does this WebCrypto engine implement X25519 key agreement? */
internal expect val webCryptoSupportsX25519: Boolean

/** Generates a [curveName] key pair via WebCrypto, returning raw-encoded keys as hex. */
internal expect suspend fun webCryptoGenerateKeyPair(curveName: String): WebCryptoKeyPair

/** Computes the raw ECDH/X25519 shared secret via WebCrypto, returning it as hex. */
internal expect suspend fun webCryptoDeriveSharedSecret(
    curveName: String,
    privateHex: String,
    peerPublicHex: String,
): String
