@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file
@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.CoreCrypto.kCCPRFHmacAlgSHA512
import platform.CoreCrypto.kCCSuccess

/** Apple PBKDF2 backed by CommonCrypto `CCKeyDerivationPBKDF`, writing straight into [WriteBuffer] memory. */
actual fun CryptoCapabilities.pbkdf2(prf: Pbkdf2Prf): Pbkdf2Support = Pbkdf2Support.Blocking(BlockingPbkdf2Ops(prf, ::pbkdf2CommonCrypto))

private fun pbkdf2CommonCrypto(
    prf: Pbkdf2Prf,
    password: ReadBuffer,
    salt: ReadBuffer,
    iterations: Int,
    length: Int,
    dest: WriteBuffer,
) {
    val ccPrf =
        when (prf) {
            Pbkdf2Prf.HmacSha256 -> kCCPRFHmacAlgSHA256
            Pbkdf2Prf.HmacSha512 -> kCCPRFHmacAlgSHA512
        }
    val passwordLen = password.remaining()
    val saltLen = salt.remaining()
    var status = kCCSuccess
    password.withRemainingBytes2(passwordLen) { pw ->
        salt.withRemainingBytes2(saltLen) { s ->
            dest.withWritablePointer(length) { out ->
                status =
                    CCKeyDerivationPBKDF(
                        kCCPBKDF2.convert(),
                        pw,
                        passwordLen.convert(),
                        s.reinterpret(),
                        saltLen.convert(),
                        ccPrf.convert(),
                        iterations.convert(),
                        out.reinterpret(),
                        length.convert(),
                    )
            }
        }
    }
    check(status == kCCSuccess) { "CCKeyDerivationPBKDF failed with status $status" }
}
