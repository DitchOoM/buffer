package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managed

/**
 * Pure-Kotlin streaming SHA-512 / SHA-384 (FIPS 180-4), used as the js/wasmJs fallback
 * because WebCrypto's `SubtleCrypto.digest` is async-only and cannot satisfy the synchronous
 * [Sha512Digest] / [Sha384Digest] / `Hmac*Mac` contracts. SHA-384 is the same compression
 * function with a distinct IV; [sha384] selects it and the digest actual reads the first 48
 * of the 64 output bytes.
 *
 * Holds **no primitive arrays**: the working state lives in a single big-endian managed [work]
 * buffer (message block in words 0..15, expanded schedule in words 16..79, read/written
 * word-at-a-time via absolute `getLong`/`set`) and eight `ULong` hash-state fields. Round
 * constants live in a shared read-only buffer ([K]). Correctness is pinned by NIST/RFC KAT.
 *
 * Not thread-safe — one instance per digest.
 */
internal class Sha512Core(
    sha384: Boolean,
) {
    private var h0: ULong
    private var h1: ULong
    private var h2: ULong
    private var h3: ULong
    private var h4: ULong
    private var h5: ULong
    private var h6: ULong
    private var h7: ULong

    init {
        val iv = if (sha384) IV384 else IV512
        var off = 0
        h0 = iv.getLong(off).toULong()
        off += WORD_BYTES
        h1 = iv.getLong(off).toULong()
        off += WORD_BYTES
        h2 = iv.getLong(off).toULong()
        off += WORD_BYTES
        h3 = iv.getLong(off).toULong()
        off += WORD_BYTES
        h4 = iv.getLong(off).toULong()
        off += WORD_BYTES
        h5 = iv.getLong(off).toULong()
        off += WORD_BYTES
        h6 = iv.getLong(off).toULong()
        off += WORD_BYTES
        h7 = iv.getLong(off).toULong()
    }

    // 128-byte message block (bytes 0..127 = schedule words 0..15) plus room for the expanded
    // schedule words 16..79 (bytes 128..639). One allocation, no arrays.
    private val work: PlatformBuffer = BufferFactory.managed().allocate(640, ByteOrder.BIG_ENDIAN)
    private var blockLen = 0
    private var totalBytes = 0L

    /** Absorbs the remaining bytes of [input] without disturbing its position. */
    fun update(input: ReadBuffer) {
        val start = input.position()
        val end = input.limit()
        var i = start
        while (i < end) {
            absorbByte(input.get(i))
            i++
        }
    }

    /** Absorbs a single byte. */
    fun absorbByte(b: Byte) {
        work.set(blockLen, b)
        blockLen++
        if (blockLen == SHA512_BLOCK_BYTES) {
            processBlock()
            blockLen = 0
        }
        totalBytes++
    }

    /** Pads and finalizes; afterwards [digestByte] returns the digest bytes. */
    fun finish() {
        // 128-bit big-endian message length in bits = totalBytes * 8.
        val lenHigh = totalBytes ushr 61
        val lenLow = totalBytes shl 3
        work.set(blockLen, 0x80.toByte())
        blockLen++
        if (blockLen > SHA512_BLOCK_BYTES - LENGTH_FIELD_BYTES) {
            while (blockLen < SHA512_BLOCK_BYTES) {
                work.set(blockLen, 0.toByte())
                blockLen++
            }
            processBlock()
            blockLen = 0
        }
        while (blockLen < SHA512_BLOCK_BYTES - LENGTH_FIELD_BYTES) {
            work.set(blockLen, 0.toByte())
            blockLen++
        }
        work.set(SHA512_BLOCK_BYTES - LENGTH_FIELD_BYTES, lenHigh) // bytes 112..119
        work.set(SHA512_BLOCK_BYTES - LOW_LENGTH_BYTES, lenLow) // bytes 120..127
        processBlock()
    }

    /** Digest byte [i] (0..63), valid only after [finish]. SHA-384 reads only 0..47. */
    fun digestByte(i: Int): Byte {
        val word =
            when (i ushr 3) {
                0 -> h0
                1 -> h1
                2 -> h2
                3 -> h3
                4 -> h4
                5 -> h5
                6 -> h6
                else -> h7
            }
        return (word shr (HIGH_BYTE_SHIFT - BITS_PER_BYTE * (i and BYTE_INDEX_MASK))).toByte()
    }

    private fun processBlock() {
        // Expand schedule words 16..79 in place (words 0..15 are the message block).
        for (t in MESSAGE_WORDS until SCHEDULE_WORDS) {
            val w15 = work.getLong((t - 15) * WORD_BYTES).toULong()
            val w2 = work.getLong((t - 2) * WORD_BYTES).toULong()
            val s0 = w15.rotateRight(1) xor w15.rotateRight(8) xor (w15 shr 7)
            val s1 = w2.rotateRight(19) xor w2.rotateRight(61) xor (w2 shr 6)
            val w16 = work.getLong((t - 16) * WORD_BYTES).toULong()
            val w7 = work.getLong((t - 7) * WORD_BYTES).toULong()
            val v = w16 + s0 + w7 + s1
            work.set(t * WORD_BYTES, v.toLong())
        }

        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        var f = h5
        var g = h6
        var hh = h7

        for (t in 0 until SCHEDULE_WORDS) {
            val w = work.getLong(t * WORD_BYTES).toULong()
            val bigS1 = e.rotateRight(14) xor e.rotateRight(18) xor e.rotateRight(41)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + bigS1 + ch + K.getLong(t * WORD_BYTES).toULong() + w
            val bigS0 = a.rotateRight(28) xor a.rotateRight(34) xor a.rotateRight(39)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = bigS0 + maj
            hh = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }

        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
        h5 += f
        h6 += g
        h7 += hh
    }

    private companion object {
        private const val WORD_BYTES = 8 // 64-bit word width
        private const val LENGTH_FIELD_BYTES = 16 // trailing 128-bit big-endian message length
        private const val LOW_LENGTH_BYTES = 8 // low 64 bits of the length field
        private const val SCHEDULE_WORDS = 80 // SHA-512 message-schedule / round count
        private const val MESSAGE_WORDS = 16 // words taken straight from the input block
        private const val HIGH_BYTE_SHIFT = 56 // shift to the most-significant byte of a 64-bit word
        private const val BITS_PER_BYTE = 8
        private const val BYTE_INDEX_MASK = 7 // i mod 8 → byte within the word
        private const val HEX_RADIX = 16

        private fun hexBufferOf(hex: String): ReadBuffer =
            BufferFactory.managed().allocate(hex.length / 2, ByteOrder.BIG_ENDIAN).apply {
                for (i in 0 until hex.length / 2) set(i, hex.substring(i * 2, i * 2 + 2).toInt(HEX_RADIX).toByte())
            }

        // First 64 bits of the fractional parts of the cube roots of the first 80 primes.
        private const val K_HEX =
            "428a2f98d728ae227137449123ef65cdb5c0fbcfec4d3b2fe9b5dba58189dbbc" +
                "3956c25bf348b53859f111f1b605d019923f82a4af194f9bab1c5ed5da6d8118" +
                "d807aa98a303024212835b0145706fbe243185be4ee4b28c550c7dc3d5ffb4e2" +
                "72be5d74f27b896f80deb1fe3b1696b19bdc06a725c71235c19bf174cf692694" +
                "e49b69c19ef14ad2efbe4786384f25e30fc19dc68b8cd5b5240ca1cc77ac9c65" +
                "2de92c6f592b02754a7484aa6ea6e4835cb0a9dcbd41fbd476f988da831153b5" +
                "983e5152ee66dfaba831c66d2db43210b00327c898fb213fbf597fc7beef0ee4" +
                "c6e00bf33da88fc2d5a79147930aa72506ca6351e003826f142929670a0e6e70" +
                "27b70a8546d22ffc2e1b21385c26c9264d2c6dfc5ac42aed53380d139d95b3df" +
                "650a73548baf63de766a0abb3c77b2a881c2c92e47edaee692722c851482353b" +
                "a2bfe8a14cf10364a81a664bbc423001c24b8b70d0f89791c76c51a30654be30" +
                "d192e819d6ef5218d69906245565a910f40e35855771202a106aa07032bbd1b8" +
                "19a4c116b8d2d0c81e376c085141ab532748774cdf8eeb9934b0bcb5e19b48a8" +
                "391c0cb3c5c95a634ed8aa4ae3418acb5b9cca4f7763e373682e6ff3d6b2b8a3" +
                "748f82ee5defb2fc78a5636f43172f6084c87814a1f0ab728cc702081a6439ec" +
                "90befffa23631e28a4506cebde82bde9bef9a3f7b2c67915c67178f2e372532b" +
                "ca273eceea26619cd186b8c721c0c207eada7dd6cde0eb1ef57d4f7fee6ed178" +
                "06f067aa72176fba0a637dc5a2c898a6113f9804bef90dae1b710b35131c471b" +
                "28db77f523047d8432caab7b40c724933c9ebe0a15c9bebc431d67c49c100d4c" +
                "4cc5d4becb3e42b6597f299cfc657e2a5fcb6fab3ad6faec6c44198c4a475817"

        private const val IV512_HEX =
            "6a09e667f3bcc908bb67ae8584caa73b3c6ef372fe94f82ba54ff53a5f1d36f1" +
                "510e527fade682d19b05688c2b3e6c1f1f83d9abfb41bd6b5be0cd19137e2179"

        private const val IV384_HEX =
            "cbbb9d5dc1059ed8629a292a367cd5079159015a3070dd17152fecd8f70e5939" +
                "67332667ffc00b318eb44a8768581511db0c2e0d64f98fa747b5481dbefa4fa4"

        val K: ReadBuffer = hexBufferOf(K_HEX)
        val IV512: ReadBuffer = hexBufferOf(IV512_HEX)
        val IV384: ReadBuffer = hexBufferOf(IV384_HEX)
    }
}
