@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CCOperation
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCSuccess
import platform.posix.size_tVar

/*
 * Apple single-block AES (ECB) backed by CommonCrypto's public one-shot CCCrypt API.
 *
 * Unlike AES-GCM (whose streaming SPI entry points are absent from Kotlin/Native's binding and need
 * the `commoncryptogcm` cinterop), ECB is one of CommonCrypto's *public* modes and is exposed
 * directly by platform.CoreCrypto — so no extra cinterop is required. We call CCCrypt with
 * kCCAlgorithmAES + kCCOptionECBMode and no padding option, over exactly one 16-byte block (the
 * common witness op validates the length/room first). ECB takes no IV, so it is passed as null.
 *
 * This file is verified on macOS CI; the cross-platform KAT suite gates it on Mac.
 */

/** Single-block AES is synchronous via CommonCrypto's CCCrypt (public ECB mode). */
actual val CryptoCapabilities.aesEcb: AesEcb get() = AesEcb.Blocking(AesEcbBlockingOps)

internal actual fun aesEcbEncryptBlock(
    key: AesEcbKey,
    block: ReadBuffer,
    dest: WriteBuffer,
): Unit = ecbCrypt(kCCEncrypt, key, block, dest)

internal actual fun aesEcbDecryptBlock(
    key: AesEcbKey,
    block: ReadBuffer,
    dest: WriteBuffer,
): Unit = ecbCrypt(kCCDecrypt, key, block, dest)

private fun ecbCrypt(
    op: CCOperation,
    key: AesEcbKey,
    block: ReadBuffer,
    dest: WriteBuffer,
) {
    memScoped {
        val moved = alloc<size_tVar>()
        var status = kCCSuccess + 1
        key.requireInMemoryMaterial().withRemainingBytes { keyPtr, keyLen ->
            block.withRemainingBytes { inPtr, _ ->
                // No padding option (kCCOptionECBMode only) — this is the bare block permutation.
                dest.withWritablePointer(AES_ECB_BLOCK_BYTES) { outPtr ->
                    status =
                        CCCrypt(
                            op,
                            kCCAlgorithmAES,
                            kCCOptionECBMode,
                            keyPtr,
                            keyLen.convert(),
                            null, // ECB uses no IV
                            inPtr,
                            AES_ECB_BLOCK_BYTES.convert(),
                            outPtr,
                            AES_ECB_BLOCK_BYTES.convert(),
                            moved.ptr,
                        )
                }
            }
        }
        check(status == kCCSuccess) { "AES-ECB failed (status=$status)" }
        check(moved.value.toInt() == AES_ECB_BLOCK_BYTES) {
            "AES-ECB produced ${moved.value} bytes, expected $AES_ECB_BLOCK_BYTES"
        }
    }
}
