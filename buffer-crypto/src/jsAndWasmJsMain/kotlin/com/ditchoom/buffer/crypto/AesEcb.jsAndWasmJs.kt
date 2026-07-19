package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/*
 * js/wasmJs single-block AES (ECB): unavailable.
 *
 * WebCrypto's SubtleCrypto exposes no ECB mode (only AES-GCM/CBC/CTR/KW), and DTLS 1.3 — the
 * motivating consumer of this primitive — does not run on the web, so ECB is deliberately not
 * polyfilled here. The aesEcb witness is Unavailable and both raw seam functions throw; the primitive
 * is unreachable by construction rather than via a runtime surprise.
 */

private const val NO_AES_ECB =
    "single-block AES (ECB) is not part of WebCrypto and is not polyfilled on the web"

/** Single-block AES (ECB) is not available on the web. */
actual val CryptoCapabilities.aesEcb: AesEcb get() = AesEcb.Unavailable

internal actual fun aesEcbEncryptBlock(
    key: AesEcbKey,
    block: ReadBuffer,
    dest: WriteBuffer,
): Unit = throw UnsupportedOperationException(NO_AES_ECB)

internal actual fun aesEcbDecryptBlock(
    key: AesEcbKey,
    block: ReadBuffer,
    dest: WriteBuffer,
): Unit = throw UnsupportedOperationException(NO_AES_ECB)
