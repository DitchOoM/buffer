package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managed

/**
 * Pure-Kotlin streaming SHA-256 (FIPS 180-4), used as the js/wasmJs fallback because
 * WebCrypto's `SubtleCrypto.digest` is async-only and cannot satisfy the synchronous
 * [Sha256Digest] / [HmacSha256Mac] contracts.
 *
 * Holds **no primitive arrays**: the working state lives in a single big-endian managed
 * [work] buffer (message block in words 0..15, expanded schedule in words 16..63, read and
 * written word-at-a-time via absolute `getInt`/`set`) and eight `UInt` hash-state fields.
 * Round constants live in a shared read-only buffer ([K]). Correctness is pinned by the
 * common NIST/RFC known-answer vectors.
 *
 * Not thread-safe — one instance per digest.
 */
internal class Sha256Core {
    private var h0 = 0x6a09e667u
    private var h1 = 0xbb67ae85u
    private var h2 = 0x3c6ef372u
    private var h3 = 0xa54ff53au
    private var h4 = 0x510e527fu
    private var h5 = 0x9b05688cu
    private var h6 = 0x1f83d9abu
    private var h7 = 0x5be0cd19u

    // 64-byte message block (bytes 0..63 = schedule words 0..15) plus room for the
    // expanded schedule words 16..63 (bytes 64..255). One allocation, no arrays.
    private val work: PlatformBuffer = BufferFactory.managed().allocate(256, ByteOrder.BIG_ENDIAN)
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
        if (blockLen == SHA256_BLOCK_BYTES) {
            processBlock()
            blockLen = 0
        }
        totalBytes++
    }

    /** Pads and finalizes; afterwards [digestByte] returns the digest bytes. */
    fun finish() {
        val bitLen = totalBytes * 8
        work.set(blockLen, 0x80.toByte())
        blockLen++
        if (blockLen > SHA256_BLOCK_BYTES - 8) {
            while (blockLen < SHA256_BLOCK_BYTES) {
                work.set(blockLen, 0.toByte())
                blockLen++
            }
            processBlock()
            blockLen = 0
        }
        while (blockLen < SHA256_BLOCK_BYTES - 8) {
            work.set(blockLen, 0.toByte())
            blockLen++
        }
        work.set(SHA256_BLOCK_BYTES - 8, bitLen) // 64-bit big-endian length at bytes 56..63
        processBlock()
    }

    /** Digest byte [i] (0..31), valid only after [finish]. */
    fun digestByte(i: Int): Byte {
        val word =
            when (i ushr 2) {
                0 -> h0
                1 -> h1
                2 -> h2
                3 -> h3
                4 -> h4
                5 -> h5
                6 -> h6
                else -> h7
            }
        return (word shr (24 - 8 * (i and 3))).toByte()
    }

    private fun processBlock() {
        // Expand schedule words 16..63 in place (words 0..15 are the message block).
        for (t in 16 until 64) {
            val w15 = work.getInt((t - 15) * 4).toUInt()
            val w2 = work.getInt((t - 2) * 4).toUInt()
            val s0 = w15.rotateRight(7) xor w15.rotateRight(18) xor (w15 shr 3)
            val s1 = w2.rotateRight(17) xor w2.rotateRight(19) xor (w2 shr 10)
            val v = work.getInt((t - 16) * 4).toUInt() + s0 + work.getInt((t - 7) * 4).toUInt() + s1
            work.set(t * 4, v.toInt())
        }

        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        var f = h5
        var g = h6
        var hh = h7

        for (t in 0 until 64) {
            val w = work.getInt(t * 4).toUInt()
            val bigS1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + bigS1 + ch + K.getInt(t * 4).toUInt() + w
            val bigS0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
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

    companion object {
        // First 32 bits of the fractional parts of the cube roots of the first 64 primes,
        // stored in a shared read-only big-endian buffer (no primitive array).
        private const val K_HEX =
            "428a2f9871374491b5c0fbcfe9b5dba53956c25b59f111f1923f82a4ab1c5ed5" +
                "d807aa9812835b01243185be550c7dc372be5d7480deb1fe9bdc06a7c19bf174" +
                "e49b69c1efbe47860fc19dc6240ca1cc2de92c6f4a7484aa5cb0a9dc76f988da" +
                "983e5152a831c66db00327c8bf597fc7c6e00bf3d5a7914706ca635114292967" +
                "27b70a852e1b21384d2c6dfc53380d13650a7354766a0abb81c2c92e92722c85" +
                "a2bfe8a1a81a664bc24b8b70c76c51a3d192e819d6990624f40e3585106aa070" +
                "19a4c1161e376c082748774c34b0bcb5391c0cb34ed8aa4a5b9cca4f682e6ff3" +
                "748f82ee78a5636f84c878148cc7020890befffaa4506cebbef9a3f7c67178f2"

        private val K: ReadBuffer =
            BufferFactory.managed().allocate(256, ByteOrder.BIG_ENDIAN).apply {
                for (i in 0 until 256) set(i, K_HEX.substring(i * 2, i * 2 + 2).toInt(16).toByte())
            }
    }
}
