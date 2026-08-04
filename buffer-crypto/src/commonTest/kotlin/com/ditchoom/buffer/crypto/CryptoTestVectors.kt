package com.ditchoom.buffer.crypto

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.fromHexString
import com.ditchoom.buffer.toHexString
import com.ditchoom.buffer.toReadBuffer

/** Test helpers that build inputs and render outputs through the buffer API only (no ByteArray). */
object CryptoTestVectors {
    /** Materializes a hex string into a read-ready buffer. */
    fun hexBuffer(hex: String): ReadBuffer = BufferFactory.Default.fromHexString(hex)

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

    /** Lowercase hex of this buffer's remaining bytes (non-destructive). The suite's short alias. */
    fun ReadBuffer.toHex(): String = toHexString()
}
