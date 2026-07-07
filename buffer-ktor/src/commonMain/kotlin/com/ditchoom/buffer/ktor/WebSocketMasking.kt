package com.ditchoom.buffer.ktor

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadWriteBuffer
import io.ktor.utils.io.ByteWriteChannel

/**
 * Applies an RFC 6455 WebSocket frame mask over this buffer's remaining bytes in place, delegating
 * to the buffer library's SIMD-accelerated [ReadWriteBuffer.xorMask].
 *
 * The XOR transform is symmetric: the same call both masks (client→server) and unmasks
 * (server→client) a payload. Position and limit are unchanged.
 *
 * @param maskingKey the 4-byte masking key, big-endian (byte 0 = most significant).
 * @param maskOffset offset into the mask cycle (0-3) for continuing a mask across fragments.
 */
public fun ReadWriteBuffer.applyWebSocketMask(
    maskingKey: Int,
    maskOffset: Int = 0,
) {
    xorMask(maskingKey, maskOffset)
}

/**
 * Masks [payload]'s remaining bytes with [maskingKey] (RFC 6455) and writes the masked bytes to
 * this [ByteWriteChannel], then flushes. The copy and mask are fused into a single pass via
 * [ReadWriteBuffer.xorMaskCopy] (SIMD-accelerated on native platforms).
 *
 * **Does not change [payload]'s position** and does not mutate [payload] — the masked bytes go to a
 * scratch buffer allocated by [factory], leaving [payload] reusable.
 *
 * @param payload the unmasked frame payload.
 * @param maskingKey the 4-byte masking key, big-endian.
 * @param factory allocator for the scratch buffer (default [BufferFactory.Default]).
 */
public suspend fun ByteWriteChannel.writeMaskedWebSocketPayload(
    payload: ReadBuffer,
    maskingKey: Int,
    factory: BufferFactory = BufferFactory.Default,
) {
    val size = payload.remaining()
    val scratch = factory.allocate(size, payload.byteOrder)
    try {
        val startPosition = payload.position()
        scratch.xorMaskCopy(payload, maskingKey)
        payload.position(startPosition)
        scratch.resetForRead()
        writeBuffer(scratch)
    } finally {
        scratch.freeNativeMemory()
    }
}
