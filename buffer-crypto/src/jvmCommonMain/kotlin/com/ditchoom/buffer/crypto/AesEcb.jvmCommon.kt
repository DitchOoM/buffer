package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import javax.crypto.Cipher

/*
 * JVM/Android single-block AES (ECB) backed by JCA.
 *
 * Uses the `AES/ECB/NoPadding` transform on a single 16-byte block — the bare AES permutation, with
 * no padding, no chaining, and no authentication (see the common `AesEcb.kt` header for the intended
 * uses and the ECB warning). The common witness op validates the block length and destination room
 * before this seam is reached; `finalInto` runs `doFinal` straight into the destination buffer.
 */

/** Single-block AES is synchronous via JCA. */
actual val CryptoCapabilities.aesEcb: AesEcb get() = AesEcb.Blocking(AesEcbBlockingOps)

internal actual fun aesEcbEncryptBlock(
    key: AesEcbKey,
    block: ReadBuffer,
    dest: WriteBuffer,
): Unit = ecbCrypt(Cipher.ENCRYPT_MODE, key, block, dest)

internal actual fun aesEcbDecryptBlock(
    key: AesEcbKey,
    block: ReadBuffer,
    dest: WriteBuffer,
): Unit = ecbCrypt(Cipher.DECRYPT_MODE, key, block, dest)

private fun ecbCrypt(
    mode: Int,
    key: AesEcbKey,
    block: ReadBuffer,
    dest: WriteBuffer,
) {
    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
    cipher.init(mode, keySpec(key.requireInMemoryMaterial(), "AES"))
    finalInto(cipher, block, dest)
}
