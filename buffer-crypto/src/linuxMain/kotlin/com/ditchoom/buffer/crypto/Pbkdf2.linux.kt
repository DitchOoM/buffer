@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file
@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.crypto.cinterop.boringssl.BCL_OK
import com.ditchoom.buffer.crypto.cinterop.boringssl.bcl_pbkdf2_hmac
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret

/** Linux PBKDF2 backed by BoringSSL `PKCS5_PBKDF2_HMAC`, writing straight into [WriteBuffer] memory. */
actual fun CryptoCapabilities.pbkdf2(prf: Pbkdf2Prf): Pbkdf2Support = Pbkdf2Support.Blocking(BlockingPbkdf2Ops(prf, ::pbkdf2BoringSsl))

private const val SHA256_BITS = 256
private const val SHA512_BITS = 512

private fun pbkdf2BoringSsl(
    prf: Pbkdf2Prf,
    password: ReadBuffer,
    salt: ReadBuffer,
    iterations: Int,
    length: Int,
    dest: WriteBuffer,
) {
    val mdBits =
        when (prf) {
            Pbkdf2Prf.HmacSha256 -> SHA256_BITS
            Pbkdf2Prf.HmacSha512 -> SHA512_BITS
        }
    val passwordLen = password.remaining()
    val saltLen = salt.remaining()
    var status = BCL_OK
    password.withRemainingBytes2(passwordLen) { pw ->
        salt.withRemainingBytes2(saltLen) { s ->
            dest.withWritablePointer(length) { out ->
                status =
                    bcl_pbkdf2_hmac(
                        mdBits,
                        pw.reinterpret(),
                        passwordLen.convert(),
                        s.reinterpret(),
                        saltLen.convert(),
                        iterations.convert(),
                        out.reinterpret(),
                        length.convert(),
                    )
            }
        }
    }
    check(status == BCL_OK) { "PKCS5_PBKDF2_HMAC failed (status=$status)" }
}
