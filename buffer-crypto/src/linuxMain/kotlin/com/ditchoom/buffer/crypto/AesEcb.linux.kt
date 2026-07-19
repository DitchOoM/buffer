@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.crypto.cinterop.boringssl.BCL_OK
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_aes_ecb_decrypt_block
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_aes_ecb_encrypt_block
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret

/*
 * Linux single-block AES (ECB) backed by BoringSSL's low-level AES_encrypt/AES_decrypt.
 *
 * The bare keyed block permutation over one 16-byte block — no IV, no chaining, no padding, no
 * authentication (see the common AesEcb.kt header for intended uses and the ECB warning). The common
 * witness op validates block length and destination room before this seam; the C wrappers
 * (bcl_aes_ecb_*) key the AES schedule and transform the single block straight into dest.
 */

/** Single-block AES is synchronous via BoringSSL on Linux. */
actual val CryptoCapabilities.aesEcb: AesEcb get() = AesEcb.Blocking(AesEcbBlockingOps)

internal actual fun aesEcbEncryptBlock(
    key: AesEcbKey,
    block: ReadBuffer,
    dest: WriteBuffer,
): Unit = ecbCrypt(encrypt = true, key.requireInMemoryMaterial(), block, dest)

internal actual fun aesEcbDecryptBlock(
    key: AesEcbKey,
    block: ReadBuffer,
    dest: WriteBuffer,
): Unit = ecbCrypt(encrypt = false, key.requireInMemoryMaterial(), block, dest)

private fun ecbCrypt(
    encrypt: Boolean,
    keyMaterial: ReadBuffer,
    block: ReadBuffer,
    dest: WriteBuffer,
) {
    memScoped {
        var status = BCL_OK - 1
        keyMaterial.withRemainingBytes { keyPtr, keyLen ->
            block.withRemainingBytes { inPtr, _ ->
                dest.withWritablePointer(AES_ECB_BLOCK_BYTES) { outPtr ->
                    status =
                        if (encrypt) {
                            bcl_aes_ecb_encrypt_block(
                                keyPtr.reinterpret(),
                                keyLen.convert(),
                                inPtr.reinterpret(),
                                outPtr.reinterpret(),
                            )
                        } else {
                            bcl_aes_ecb_decrypt_block(
                                keyPtr.reinterpret(),
                                keyLen.convert(),
                                inPtr.reinterpret(),
                                outPtr.reinterpret(),
                            )
                        }
                }
            }
        }
        check(status == BCL_OK) { "AES-ECB failed (status=$status)" }
    }
}
