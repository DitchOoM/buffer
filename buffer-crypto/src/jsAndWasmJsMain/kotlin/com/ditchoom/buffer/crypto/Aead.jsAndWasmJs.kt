package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/*
 * js/wasmJs AEAD.
 *
 * AES-GCM is provided only through the asynchronous WebCrypto path: SubtleCrypto.encrypt /
 * decrypt are Promise-based, so there is no synchronous AES-GCM on the web — supportsSyncAesGcm
 * is false and the sync expect funs throw. Use aesGcmSealAsync / aesGcmOpenAsync, which await
 * WebCrypto.
 *
 * ChaCha20-Poly1305 is not part of WebCrypto (w3c/webcrypto#223) and is never polyfilled:
 * supportsChaChaPoly / supportsSyncChaChaPoly are false and every ChaCha entry point — sync and
 * async — throws UnsupportedOperationException.
 *
 * The bridge marshals key/nonce/AAD/data as lowercase hex strings to the per-target WebCrypto
 * shim and parses a hex result back, so the same jsAndWasmJsMain logic compiles for both the JS
 * and the Wasm backend without typed-array interop differences leaking in. No ByteArray crosses
 * the boundary, and the tag stays attached to the ciphertext (WebCrypto appends it), so
 * plaintext is only produced after WebCrypto verifies it.
 */

private const val NO_CHACHA_POLY = "ChaCha20-Poly1305 is not part of WebCrypto and is not polyfilled"

internal actual val supportsSyncAesGcm: Boolean = false

internal actual val supportsChaChaPoly: Boolean = false

internal actual val supportsSyncChaChaPoly: Boolean = false

/** AES-GCM on the web is async-only (WebCrypto `SubtleCrypto` is Promise-based). */
actual val CryptoCapabilities.aesGcm: Aead<AesGcmKey> get() = Aead.AsyncOnly(AesGcmAsyncOps)

/** ChaCha20-Poly1305 is not part of WebCrypto and is never polyfilled. */
actual val CryptoCapabilities.chaChaPoly: OptionalAead<ChaChaPolyKey> get() = OptionalAead.Unavailable

internal actual fun aesGcmSeal(
    key: AesGcmKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    dest: WriteBuffer,
): Unit = throw UnsupportedOperationException("synchronous AES-GCM is unavailable on the web; use aesGcmSealAsync")

internal actual fun aesGcmOpen(
    key: AesGcmKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    dest: WriteBuffer,
): Unit = throw UnsupportedOperationException("synchronous AES-GCM is unavailable on the web; use aesGcmOpenAsync")

internal actual fun chaChaPolySeal(
    key: ChaChaPolyKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    dest: WriteBuffer,
): Unit = throw UnsupportedOperationException(NO_CHACHA_POLY)

internal actual fun chaChaPolyOpen(
    key: ChaChaPolyKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    dest: WriteBuffer,
): Unit = throw UnsupportedOperationException(NO_CHACHA_POLY)

internal actual suspend fun aesGcmSealWithNonceAsync(
    key: AesGcmKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    plaintext: ReadBuffer,
    factory: BufferFactory,
): PlatformBuffer {
    require(nonce.remaining() == AEAD_NONCE_BYTES) {
        "nonce must be $AEAD_NONCE_BYTES bytes, was ${nonce.remaining()}"
    }
    val ctTagHex =
        webCryptoAesGcmEncrypt(
            keyHex = key.requireInMemoryMaterial().toHexRemaining(),
            ivHex = nonce.toHexRemaining(),
            aadHex = aad?.toHexRemaining() ?: "",
            plaintextHex = plaintext.toHexRemaining(),
        )
    val out = factory.allocate(plaintext.remaining() + AEAD_TAG_BYTES)
    out.writeHex(ctTagHex)
    out.resetForRead()
    return out
}

internal actual suspend fun aesGcmOpenWithNonceAsync(
    key: AesGcmKey,
    nonce: ReadBuffer,
    aad: ReadBuffer?,
    ciphertextAndTag: ReadBuffer,
    factory: BufferFactory,
): PlatformBuffer {
    require(nonce.remaining() == AEAD_NONCE_BYTES) {
        "nonce must be $AEAD_NONCE_BYTES bytes, was ${nonce.remaining()}"
    }
    val ptHex =
        webCryptoAesGcmDecrypt(
            keyHex = key.requireInMemoryMaterial().toHexRemaining(),
            ivHex = nonce.toHexRemaining(),
            aadHex = aad?.toHexRemaining() ?: "",
            ciphertextAndTagHex = ciphertextAndTag.toHexRemaining(),
        ) ?: throw VerificationFailed()
    val out = factory.allocate(maxOf(0, ciphertextAndTag.remaining() - AEAD_TAG_BYTES))
    out.writeHex(ptHex)
    out.resetForRead()
    return out
}

// =============================================================================
// hex marshalling (shared js/wasmJs)
// =============================================================================

private const val HEX = "0123456789abcdef"
private const val HEX_RADIX = 16
private const val NIBBLE_BITS = 4

/** Lowercase hex of this buffer's remaining bytes (non-destructive). */
internal fun ReadBuffer.toHexRemaining(): String {
    val start = position()
    val n = remaining()
    val sb = StringBuilder(n * 2)
    for (i in 0 until n) {
        val v = get(start + i).toInt() and 0xFF
        sb.append(HEX[v ushr NIBBLE_BITS])
        sb.append(HEX[v and 0xF])
    }
    return sb.toString()
}

/** Writes the bytes decoded from a lowercase/uppercase hex string at the buffer's position. */
internal fun WriteBuffer.writeHex(hex: String) {
    var i = 0
    while (i < hex.length) {
        val hi = hex[i].digitToInt(HEX_RADIX)
        val lo = hex[i + 1].digitToInt(HEX_RADIX)
        writeByte(((hi shl NIBBLE_BITS) or lo).toByte())
        i += 2
    }
}

/**
 * Per-target WebCrypto AES-GCM encrypt: takes hex inputs, returns hex `ciphertext ‖ tag`
 * (WebCrypto appends the 16-byte tag). Awaits the `SubtleCrypto.encrypt` Promise.
 */
internal expect suspend fun webCryptoAesGcmEncrypt(
    keyHex: String,
    ivHex: String,
    aadHex: String,
    plaintextHex: String,
): String

/**
 * Per-target WebCrypto AES-GCM decrypt: takes hex `ciphertext ‖ tag` and returns the hex
 * plaintext, or `null` if WebCrypto rejects the tag (which the caller turns into the opaque
 * [VerificationFailed]). WebCrypto verifies the tag inside `decrypt`, so plaintext is produced
 * only on success.
 */
internal expect suspend fun webCryptoAesGcmDecrypt(
    keyHex: String,
    ivHex: String,
    aadHex: String,
    ciphertextAndTagHex: String,
): String?
