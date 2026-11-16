package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.toHexString

/*
 * js/wasmJs PBKDF2 over WebCrypto `deriveBits`, which is Promise-based, so the witness is
 * AsyncOnly. Password and salt cross the boundary as hex Strings, with the same zeroization
 * limitation documented for the AEAD bridge: those copies cannot be wiped.
 */

actual fun CryptoCapabilities.pbkdf2(prf: Pbkdf2Prf): Pbkdf2Support = Pbkdf2Support.AsyncOnly(webCryptoOps(prf))

private fun webCryptoOps(prf: Pbkdf2Prf) = AsyncPbkdf2Ops(prf, ::pbkdf2WebCrypto)

private suspend fun pbkdf2WebCrypto(
    prf: Pbkdf2Prf,
    password: ReadBuffer,
    salt: ReadBuffer,
    iterations: Int,
    length: Int,
    dest: WriteBuffer,
) {
    val hashName =
        when (prf) {
            Pbkdf2Prf.HmacSha256 -> "SHA-256"
            Pbkdf2Prf.HmacSha512 -> "SHA-512"
        }
    val derivedHex =
        webCryptoPbkdf2(
            hashName = hashName,
            passwordHex = password.toHexString(),
            saltHex = salt.toHexString(),
            iterations = iterations,
            bits = length * Byte.SIZE_BITS,
        )
    dest.writeHex(derivedHex)
}

/**
 * Per-target WebCrypto PBKDF2: imports the hex password as a `PBKDF2` key and awaits
 * `SubtleCrypto.deriveBits({ name: 'PBKDF2', hash, salt, iterations }, key, bits)`, returning the
 * derived bits as lowercase hex.
 */
internal expect suspend fun webCryptoPbkdf2(
    hashName: String,
    passwordHex: String,
    saltHex: String,
    iterations: Int,
    bits: Int,
): String
