package com.ditchoom.buffer.ktor

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.managed
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WebSocketMaskingTest {
    private val maskingKey = 0x37FA213D

    @Test
    fun applyWebSocketMask_isSymmetric() {
        val original = bytesOf(64)
        val buffer = BufferFactory.managed().allocate(64)
        buffer.writeBytes(original)
        buffer.position(0)

        buffer.applyWebSocketMask(maskingKey)
        buffer.position(0)
        val masked = ByteArray(64) { buffer.readByte() }
        assertFalse(original.contentEquals(masked), "mask must change the bytes")

        buffer.position(0)
        buffer.applyWebSocketMask(maskingKey)
        buffer.position(0)
        val unmasked = ByteArray(64) { buffer.readByte() }
        assertContentEquals(original, unmasked, "re-applying the mask must restore the original")
    }

    @Test
    fun writeMaskedWebSocketPayload_roundTrip() =
        runTest {
            val original = bytesOf(200)
            val payload = BufferFactory.Default.allocate(200)
            payload.writeBytes(original)
            payload.resetForRead()

            val channel = ByteChannel(autoFlush = true)
            channel.writeMaskedWebSocketPayload(payload, maskingKey)
            channel.flushAndClose()

            // Read the masked bytes back and unmask to recover the payload.
            val masked = channel.readRemainingBuffer(BufferFactory.managed())
            masked.applyWebSocketMask(maskingKey)
            assertContentEquals(original, masked.remainingBytes())
        }

    @Test
    fun writeMaskedWebSocketPayload_doesNotMutatePayload() =
        runTest {
            val original = bytesOf(120)
            val payload = BufferFactory.managed().allocate(120)
            payload.writeBytes(original)
            payload.resetForRead()
            val startPosition = payload.position()

            val channel = ByteChannel(autoFlush = true)
            channel.writeMaskedWebSocketPayload(payload, maskingKey)
            channel.flushAndClose()

            assertEquals(startPosition, payload.position(), "payload position must be unchanged")
            assertContentEquals(original, payload.remainingBytes(), "payload bytes must be unchanged")
        }
}
