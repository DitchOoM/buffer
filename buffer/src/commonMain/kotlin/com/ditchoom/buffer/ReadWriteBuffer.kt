package com.ditchoom.buffer

/**
 * A buffer that supports both read and write operations.
 *
 * This interface combines [ReadBuffer] and [WriteBuffer] capabilities,
 * providing a common type for buffers that can be both written to and read from.
 *
 * Both [PlatformBuffer] and [com.ditchoom.buffer.pool.PooledBuffer] implement
 * this interface, making it useful for APIs that need to allocate and return
 * buffers without caring about the specific buffer type.
 */
interface ReadWriteBuffer :
    ReadBuffer,
    WriteBuffer {
    /**
     * The total capacity of this buffer in bytes.
     */
    val capacity: Int

    /**
     * XORs bytes in `[position, limit)` in-place with a repeating 4-byte mask.
     *
     * The mask is applied in big-endian order: mask byte 0 (MSB) is applied to
     * position+0, mask byte 1 to position+1, etc., repeating every 4 bytes.
     *
     * Position and limit are unchanged after this operation.
     *
     * This is commonly used for WebSocket frame masking (RFC 6455).
     *
     * @param mask The 4-byte XOR mask in big-endian order
     */
    fun xorMask(mask: Int) {
        if (mask == 0) return
        val pos = position()
        val lim = limit()
        val size = lim - pos

        val maskByte0 = (mask ushr 24).toByte()
        val maskByte1 = (mask ushr 16).toByte()
        val maskByte2 = (mask ushr 8).toByte()
        val maskByte3 = mask.toByte()

        for (i in 0 until size) {
            val maskByte =
                when (i and 3) {
                    0 -> maskByte0
                    1 -> maskByte1
                    2 -> maskByte2
                    else -> maskByte3
                }
            set(pos + i, (get(pos + i).toInt() xor maskByte.toInt()).toByte())
        }
    }
}
