package com.ditchoom.buffer

/**
 * A buffer that supports both read and write operations.
 *
 * This interface combines [ReadBuffer] and [WriteBuffer] capabilities,
 * providing a common type for buffers that can be both written to and read from.
 *
 * Both [PlatformBuffer] and pool-acquired buffers implement this interface,
 * making it useful for APIs that need to allocate and return buffers without
 * caring about the specific buffer type.
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
     * @param maskOffset Byte offset into the mask cycle (0-3). Allows masking
     *   chunked data where each chunk continues the mask cycle from the previous chunk.
     */
    fun xorMask(
        mask: Int,
        maskOffset: Int = 0,
    ) {
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
                when ((i + maskOffset) and 3) {
                    0 -> maskByte0
                    1 -> maskByte1
                    2 -> maskByte2
                    else -> maskByte3
                }
            set(pos + i, (get(pos + i).toInt() xor maskByte.toInt()).toByte())
        }
    }

    /**
     * Reads bytes from [source] `[source.position, source.limit)`, XORs with
     * the repeating 4-byte [mask], and writes the result into this buffer at
     * the current position. Advances both positions by `source.remaining()`.
     *
     * This fuses copy + mask into a single pass, avoiding a separate xorMask() call.
     *
     * @param source The source buffer to read from
     * @param mask The 4-byte XOR mask in big-endian order
     * @param maskOffset Byte offset into the mask cycle (0-3)
     */
    fun xorMaskCopy(
        source: ReadBuffer,
        mask: Int,
        maskOffset: Int = 0,
    ) {
        val size = source.remaining()
        if (size == 0) return
        if (mask == 0) {
            write(source)
            return
        }

        val srcPos = source.position()
        val dstPos = position()

        // Use bulk Long XOR when byte orders match (8x fewer iterations)
        if (source.byteOrder == byteOrder) {
            val maskLong = buildMaskLong(mask, maskOffset, byteOrder == ByteOrder.LITTLE_ENDIAN)
            bulkXorMaskCopy(
                srcPos = srcPos,
                dstPos = dstPos,
                size = size,
                maskLong = maskLong,
                mask = mask,
                maskOffset = maskOffset,
                srcGetLong = { source.getLong(it) },
                dstSetLong = { idx, value -> set(idx, value) },
                srcGetByte = { source.get(it) },
                dstSetByte = { idx, value -> set(idx, value) },
            )
        } else {
            // Byte-at-a-time fallback for mixed byte orders
            val maskByte0 = (mask ushr 24).toByte()
            val maskByte1 = (mask ushr 16).toByte()
            val maskByte2 = (mask ushr 8).toByte()
            val maskByte3 = mask.toByte()
            for (i in 0 until size) {
                val maskByte =
                    when ((i + maskOffset) and 3) {
                        0 -> maskByte0
                        1 -> maskByte1
                        2 -> maskByte2
                        else -> maskByte3
                    }
                set(dstPos + i, (source.get(srcPos + i).toInt() xor maskByte.toInt()).toByte())
            }
        }
        source.position(srcPos + size)
        position(dstPos + size)
    }
}
