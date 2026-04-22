package com.ditchoom.buffer

/**
 * Pure-Kotlin streaming SHA-1 (RFC 3174) that reads from [ReadBuffer]s
 * and writes the 20-byte digest into a [WriteBuffer].
 *
 * Exists so callers (WebSocket handshake accept-key, MQTT v5 AUTH, etc.)
 * don't have to materialise their input as a `ByteArray` to feed a
 * platform crypto API or to use the legacy `ByteArray → ByteArray` SHA-1
 * helpers scattered across the code base.
 *
 * Thread-unsafe. Reuse via [reset]. Scratch state is two fixed-size
 * [IntArray]s per instance (16 words for the current block, 80 for the
 * message schedule); no per-update allocation.
 *
 * Typical one-shot usage:
 * ```kotlin
 * val digest = BufferFactory.Default.allocate(20)
 * digest.writeSha1Of(input)
 * digest.resetForRead()
 * ```
 */
class Sha1 {
    private var h0 = INITIAL_H0
    private var h1 = INITIAL_H1
    private var h2 = INITIAL_H2
    private var h3 = INITIAL_H3
    private var h4 = INITIAL_H4
    private val block = IntArray(16) // 16 big-endian words = 64-byte block
    private val w = IntArray(80) // SHA-1 message schedule (reused per block)
    private var byteIndexInBlock = 0 // 0..63, where the next input byte lands
    private var totalBytes = 0L
    private var finished = false

    /**
     * Feeds the remaining bytes of [input] into the hash, advancing
     * `input.position` by `input.remaining()`. May be called repeatedly.
     */
    fun update(input: ReadBuffer): Sha1 {
        check(!finished) { "Sha1 already finished — call reset() before reusing" }
        val n = input.remaining()
        totalBytes += n
        var remaining = n
        while (remaining > 0) {
            val b = input.readByte().toInt() and 0xFF
            val wordIndex = byteIndexInBlock ushr 2
            val shift = 24 - ((byteIndexInBlock and 3) shl 3)
            block[wordIndex] = block[wordIndex] or (b shl shift)
            byteIndexInBlock++
            if (byteIndexInBlock == 64) {
                processBlock()
                byteIndexInBlock = 0
                block.fill(0)
            }
            remaining--
        }
        return this
    }

    /**
     * Writes the 20-byte digest at [output]'s current position and
     * advances it by 20. Fails with [BufferOverflowException] if fewer
     * than 20 bytes remain. After finishing the hash is locked; call
     * [reset] before reusing.
     */
    fun finish(output: WriteBuffer): WriteBuffer {
        check(!finished) { "Sha1 already finished" }
        output.checkWriteBounds(DIGEST_SIZE)
        applyPaddingAndLength()
        finished = true
        writeWord(output, h0)
        writeWord(output, h1)
        writeWord(output, h2)
        writeWord(output, h3)
        writeWord(output, h4)
        return output
    }

    /** Resets the hash so this instance can be reused for a new input. */
    fun reset() {
        h0 = INITIAL_H0
        h1 = INITIAL_H1
        h2 = INITIAL_H2
        h3 = INITIAL_H3
        h4 = INITIAL_H4
        block.fill(0)
        byteIndexInBlock = 0
        totalBytes = 0L
        finished = false
    }

    private fun applyPaddingAndLength() {
        val bitLength = totalBytes * 8
        // Append 0x80 terminator bit
        val wordIndex = byteIndexInBlock ushr 2
        val shift = 24 - ((byteIndexInBlock and 3) shl 3)
        block[wordIndex] = block[wordIndex] or (0x80 shl shift)
        byteIndexInBlock++

        // If there isn't room for the 8-byte length, finish this block and start a new one
        if (byteIndexInBlock > 56) {
            processBlock()
            block.fill(0)
            byteIndexInBlock = 0
        }
        // Write bit length in the last two words (big-endian 64-bit)
        block[14] = (bitLength ushr 32).toInt()
        block[15] = bitLength.toInt()
        processBlock()
    }

    private fun processBlock() {
        for (i in 0 until 16) w[i] = block[i]
        for (i in 16 until 80) {
            w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
        }

        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4

        for (i in 0 until 80) {
            val f: Int
            val k: Int
            when {
                i < 20 -> {
                    f = (b and c) or (b.inv() and d)
                    k = K0
                }
                i < 40 -> {
                    f = b xor c xor d
                    k = K1
                }
                i < 60 -> {
                    f = (b and c) or (b and d) or (c and d)
                    k = K2
                }
                else -> {
                    f = b xor c xor d
                    k = K3
                }
            }
            val temp = a.rotateLeft(5) + f + e + k + w[i]
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

    private fun writeWord(
        output: WriteBuffer,
        word: Int,
    ) {
        output.writeByte((word ushr 24).toByte())
        output.writeByte((word ushr 16).toByte())
        output.writeByte((word ushr 8).toByte())
        output.writeByte(word.toByte())
    }

    companion object {
        const val DIGEST_SIZE: Int = 20
        private const val INITIAL_H0 = 0x67452301
        private val INITIAL_H1 = 0xEFCDAB89.toInt()
        private val INITIAL_H2 = 0x98BADCFE.toInt()
        private const val INITIAL_H3 = 0x10325476
        private val INITIAL_H4 = 0xC3D2E1F0.toInt()
        private const val K0 = 0x5A827999
        private val K1 = 0x6ED9EBA1
        private val K2 = 0x8F1BBCDC.toInt()
        private val K3 = 0xCA62C1D6.toInt()
    }
}

/**
 * One-shot convenience: hashes the remaining bytes of [input] and writes
 * the 20-byte digest at this buffer's current position. Consumes
 * `input.remaining()` bytes and advances this buffer by [Sha1.DIGEST_SIZE].
 */
fun WriteBuffer.writeSha1Of(input: ReadBuffer): WriteBuffer {
    val sha = Sha1()
    sha.update(input)
    return sha.finish(this)
}
