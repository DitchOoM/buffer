package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.toReadBuffer

/** Test helpers that build inputs and render outputs through the buffer API only (no ByteArray). */
object CryptoTestVectors {
    /** Materializes a hex string into a read-ready buffer via byte writes. */
    fun hexBuffer(hex: String): ReadBuffer {
        val n = hex.length / 2
        val b = BufferFactory.Default.allocate(n)
        for (i in 0 until n) b.writeByte(hex.substring(i * 2, i * 2 + 2).toInt(16).toByte())
        b.resetForRead()
        return b
    }

    /** A read-ready buffer of [count] bytes each equal to [value]. */
    fun repeatedByte(
        value: Int,
        count: Int,
    ): ReadBuffer {
        val b = BufferFactory.Default.allocate(count)
        repeat(count) { b.writeByte(value.toByte()) }
        b.resetForRead()
        return b
    }

    /** A read-ready buffer of [text]'s UTF-8 bytes. */
    fun ascii(text: String): ReadBuffer = text.toReadBuffer()

    /** Lowercase hex of this buffer's remaining bytes (non-destructive). */
    fun ReadBuffer.toHex(): String {
        val hex = "0123456789abcdef"
        val start = position()
        val n = remaining()
        val sb = StringBuilder(n * 2)
        for (i in 0 until n) {
            val v = get(start + i).toInt() and 0xFF
            sb.append(hex[v ushr 4])
            sb.append(hex[v and 0xF])
        }
        return sb.toString()
    }
}
