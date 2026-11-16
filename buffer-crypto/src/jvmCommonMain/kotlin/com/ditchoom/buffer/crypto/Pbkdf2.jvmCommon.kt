@file:Suppress("MatchingDeclarationName") // MPP platform-suffixed actual file

package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import javax.crypto.Mac
import javax.crypto.SecretKey

/*
 * JVM/Android PBKDF2: the RFC 8018 §5.2 block loop over the JCA `Mac`, not
 * `SecretKeyFactory("PBKDF2WithHmacSHA…")`. That factory only accepts a `char[]` password
 * (`PBEKeySpec`) which the provider encodes as UTF-8, so a password that is not valid UTF-8
 * cannot be represented byte-exactly, and `PBEKeySpec` also rejects an empty salt. Passwords
 * here are bytes. The HMAC itself is still the platform's (SunJCE / Conscrypt), and one
 * keyed `Mac` is reused across every iteration, so the cost per iteration is the HMAC's.
 */

actual fun CryptoCapabilities.pbkdf2(prf: Pbkdf2Prf): Pbkdf2Support = Pbkdf2Support.Blocking(BlockingPbkdf2Ops(prf, ::pbkdf2Jca))

private fun jcaMacName(prf: Pbkdf2Prf): String =
    when (prf) {
        Pbkdf2Prf.HmacSha256 -> "HmacSHA256"
        Pbkdf2Prf.HmacSha512 -> "HmacSHA512"
    }

private fun pbkdf2Jca(
    prf: Pbkdf2Prf,
    password: ReadBuffer,
    salt: ReadBuffer,
    iterations: Int,
    length: Int,
    dest: WriteBuffer,
) {
    val hLen = prf.blockBytes
    val mac = Mac.getInstance(jcaMacName(prf))
    // An owned copy, wiped below: `toByteArray()` may alias the caller's backing array.
    val start = password.position()
    val passwordBytes = ByteArray(password.remaining()) { password.get(start + it) }
    try {
        mac.init(RawHmacKey(passwordBytes, mac.algorithm))
    } finally {
        passwordBytes.fill(0)
    }
    val u = ByteArray(hLen)
    val t = ByteArray(hLen)
    val blockIndex = ByteArray(Int.SIZE_BYTES)
    try {
        val blocks = (length + hLen - 1) / hLen
        for (i in 1..blocks) {
            blockIndex[0] = (i ushr 24).toByte()
            blockIndex[1] = (i ushr 16).toByte()
            blockIndex[2] = (i ushr 8).toByte()
            blockIndex[3] = i.toByte()
            mac.updateRemaining(salt)
            mac.update(blockIndex)
            mac.doFinal(u, 0)
            u.copyInto(t)
            for (round in 2..iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (k in 0 until hLen) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            dest.writeBytes(t, 0, minOf(hLen, length - (i - 1) * hLen))
        }
    } finally {
        u.fill(0)
        t.fill(0)
        mac.reset()
    }
}

/**
 * A raw HMAC key that, unlike `SecretKeySpec`, accepts zero bytes (an empty password is a valid
 * HMAC key). The JCA `Mac` copies the encoding during `init`, so the copy returned is the only one
 * the provider sees.
 */
private class RawHmacKey(
    private val bytes: ByteArray,
    private val algorithmName: String,
) : SecretKey {
    override fun getAlgorithm(): String = algorithmName

    override fun getFormat(): String = "RAW"

    override fun getEncoded(): ByteArray = bytes.copyOf()
}
