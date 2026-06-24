package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.WriteBuffer
import java.security.SecureRandom

private val secureRandom = SecureRandom()

/**
 * JVM/Android CSPRNG backed by [java.security.SecureRandom]. Fills the buffer eight bytes at
 * a time via [SecureRandom.nextLong] so no intermediate `ByteArray` is allocated — the random
 * bytes are written straight into the destination buffer.
 */
actual fun cryptoRandomInto(dest: WriteBuffer) {
    var remaining = dest.remaining()
    while (remaining >= Long.SIZE_BYTES) {
        dest.writeLong(secureRandom.nextLong())
        remaining -= Long.SIZE_BYTES
    }
    if (remaining > 0) {
        var bits = secureRandom.nextLong()
        repeat(remaining) {
            dest.writeByte(bits.toByte())
            bits = bits ushr Byte.SIZE_BITS
        }
    }
}
