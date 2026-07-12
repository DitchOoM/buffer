package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.managed

/**
 * Pure-Kotlin streaming SHA-1 (FIPS 180-4), used as the js/wasmJs fallback because WebCrypto's
 * `SubtleCrypto.digest` is async-only and cannot satisfy the synchronous [HmacSha1Mac] contract.
 *
 * SHA-1 is retained only for the interop obligations documented on [HmacSha1Mac] (STUN/TURN
 * MESSAGE-INTEGRITY, legacy SRTP); it is not a general-purpose digest.
 *
 * Holds **no primitive arrays**: the working state lives in a single big-endian managed [work]
 * buffer (message block in words 0..15, expanded schedule in words 16..79, read and written
 * word-at-a-time via absolute `getInt`/`set`) and five `UInt` hash-state fields. Correctness is
 * pinned by the common NIST/RFC known-answer vectors.
 *
 * Not thread-safe — one instance per digest.
 */
internal class Sha1Core {
    private var h0 = 0x67452301u
    private var h1 = 0xEFCDAB89u
    private var h2 = 0x98BADCFEu
    private var h3 = 0x10325476u
    private var h4 = 0xC3D2E1F0u

    // 64-byte message block (bytes 0..63 = schedule words 0..15) plus room for the
    // expanded schedule words 16..79 (bytes 64..319). One allocation, no arrays.
    private val work: PlatformBuffer = BufferFactory.managed().allocate(SCHEDULE_BYTES, ByteOrder.BIG_ENDIAN)
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
        if (blockLen == SHA1_BLOCK_BYTES) {
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
        if (blockLen > SHA1_BLOCK_BYTES - LENGTH_FIELD_BYTES) {
            while (blockLen < SHA1_BLOCK_BYTES) {
                work.set(blockLen, 0.toByte())
                blockLen++
            }
            processBlock()
            blockLen = 0
        }
        while (blockLen < SHA1_BLOCK_BYTES - LENGTH_FIELD_BYTES) {
            work.set(blockLen, 0.toByte())
            blockLen++
        }
        work.set(SHA1_BLOCK_BYTES - LENGTH_FIELD_BYTES, bitLen) // 64-bit big-endian length at bytes 56..63
        processBlock()
    }

    /** Digest byte [i] (0..19), valid only after [finish]. */
    fun digestByte(i: Int): Byte {
        val word =
            when (i ushr 2) {
                0 -> h0
                1 -> h1
                2 -> h2
                3 -> h3
                else -> h4
            }
        return (word shr (HIGH_BYTE_SHIFT - BITS_PER_BYTE * (i and BYTE_INDEX_MASK))).toByte()
    }

    private fun processBlock() {
        // Expand schedule words 16..79 in place (words 0..15 are the message block).
        for (t in MESSAGE_WORDS until SCHEDULE_WORDS) {
            val x =
                work.getInt((t - 3) * WORD_BYTES).toUInt() xor
                    work.getInt((t - 8) * WORD_BYTES).toUInt() xor
                    work.getInt((t - 14) * WORD_BYTES).toUInt() xor
                    work.getInt((t - 16) * WORD_BYTES).toUInt()
            work.set(t * WORD_BYTES, x.rotateLeft(1).toInt())
        }

        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4

        for (t in 0 until SCHEDULE_WORDS) {
            val f: UInt
            val k: UInt
            when {
                t < ROUND1_END -> {
                    f = (b and c) or (b.inv() and d)
                    k = K1
                }
                t < ROUND2_END -> {
                    f = b xor c xor d
                    k = K2
                }
                t < ROUND3_END -> {
                    f = (b and c) or (b and d) or (c and d)
                    k = K3
                }
                else -> {
                    f = b xor c xor d
                    k = K4
                }
            }
            val temp = a.rotateLeft(5) + f + e + k + work.getInt(t * WORD_BYTES).toUInt()
            e = d
            d = c
            c = b.rotateLeft(30)
            b = a
            a = temp
        }

        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
    }

    companion object {
        private const val LENGTH_FIELD_BYTES = 8 // trailing 64-bit big-endian message length
        private const val SCHEDULE_WORDS = 80 // SHA-1 message-schedule / round count
        private const val MESSAGE_WORDS = 16 // words taken straight from the input block
        private const val WORD_BYTES = 4 // 32-bit word width
        private const val SCHEDULE_BYTES = SCHEDULE_WORDS * WORD_BYTES // 80 words × 4 bytes
        private const val HIGH_BYTE_SHIFT = 24 // shift to the most-significant byte of a word
        private const val BITS_PER_BYTE = 8
        private const val BYTE_INDEX_MASK = 3 // i mod 4 → byte within the word

        // Per-round boundaries and constants (FIPS 180-4 §6.1.2).
        private const val ROUND1_END = 20
        private const val ROUND2_END = 40
        private const val ROUND3_END = 60
        private const val K1 = 0x5A827999u
        private const val K2 = 0x6ED9EBA1u
        private const val K3 = 0x8F1BBCDCu
        private const val K4 = 0xCA62C1D6u
    }
}
