package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.DispatchOnPacket
import com.ditchoom.buffer.codec.test.protocols.DispatchOnPacketCodec
import com.ditchoom.buffer.codec.test.protocols.DnsFlags
import com.ditchoom.buffer.codec.test.protocols.DnsHeader
import com.ditchoom.buffer.codec.test.protocols.DnsHeaderCodec
import com.ditchoom.buffer.codec.test.protocols.SimpleHeader
import com.ditchoom.buffer.codec.test.protocols.SimpleHeaderCodec
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Runtime tests for generated peekFrameSize functions.
 * Validates that peekFrameSize returns correct byte counts when called
 * against a real StreamProcessor, not just that the code compiles.
 */
class PeekFrameSizeRoundTripTest {
    private fun withStream(block: (StreamProcessor) -> Unit) {
        val pool = BufferPool()
        val stream = StreamProcessor.create(pool)
        try {
            block(stream)
        } finally {
            stream.release()
            pool.clear()
        }
    }

    // ──────────────────────── Fixed-size messages ────────────────────────

    @Test
    fun `fixed-size message returns constant`() {
        // DnsHeader: 6 fields × 2 bytes = 12 bytes, always fixed
        withStream { stream ->
            val buffer = BufferFactory.Default.allocate(12, ByteOrder.BIG_ENDIAN)
            DnsHeaderCodec.encode(buffer, DnsHeader(0x1234u, DnsFlags(0u), 1u, 0u, 0u, 0u))
            buffer.resetForRead()
            stream.append(buffer)

            val size = DnsHeaderCodec.peekFrameSize(stream)
            assertEquals(12, size)
        }
    }

    @Test
    fun `fixed-size message returns constant even with extra data`() {
        // SimpleHeader: 7 bytes (UByte + UShort + UInt)
        withStream { stream ->
            val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
            SimpleHeaderCodec.encode(buffer, SimpleHeader(1u, 2u, 3u))
            // Write extra garbage after
            buffer.writeByte(0x00)
            buffer.writeByte(0x00)
            buffer.resetForRead()
            stream.append(buffer)

            val size = SimpleHeaderCodec.peekFrameSize(stream)
            assertEquals(7, size)
        }
    }

    // ──────────────────────── @DispatchOn sealed dispatch ────────────────────────

    @Test
    fun `dispatch peekFrameSize routes to correct variant - Connect`() {
        withStream { stream ->
            val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
            // TypeConnect: header(1) + protocolLevel(1) + keepAlive(2) = 4 bytes
            DispatchOnPacketCodec.encode(buffer, DispatchOnPacket.TypeConnect(4u, 60u))
            buffer.resetForRead()
            stream.append(buffer)

            val size = DispatchOnPacketCodec.peekFrameSize(stream)
            assertNotNull(size)
            assertEquals(4, size) // 1 (discriminator) + 1 (protocolLevel) + 2 (keepAlive)
        }
    }

    @Test
    fun `dispatch peekFrameSize routes to correct variant - ConnAck`() {
        withStream { stream ->
            val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
            // TypeConnAck: header(1) + sessionPresent(1) + returnCode(1) = 3 bytes
            DispatchOnPacketCodec.encode(buffer, DispatchOnPacket.TypeConnAck(0u, 0u))
            buffer.resetForRead()
            stream.append(buffer)

            val size = DispatchOnPacketCodec.peekFrameSize(stream)
            assertNotNull(size)
            assertEquals(3, size) // 1 (discriminator) + 1 + 1
        }
    }

    @Test
    fun `dispatch peekFrameSize routes to correct variant - PubAck`() {
        withStream { stream ->
            val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
            // TypePubAck: header(1) + packetId(2) = 3 bytes
            DispatchOnPacketCodec.encode(buffer, DispatchOnPacket.TypePubAck(42u))
            buffer.resetForRead()
            stream.append(buffer)

            val size = DispatchOnPacketCodec.peekFrameSize(stream)
            assertNotNull(size)
            assertEquals(3, size) // 1 (discriminator) + 2 (packetId)
        }
    }

    @Test
    fun `dispatch peekFrameSize returns null for unknown type`() {
        withStream { stream ->
            val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
            buffer.writeByte(0xF0.toByte()) // unknown packet type 15
            buffer.writeByte(0x00)
            buffer.writeByte(0x00)
            buffer.resetForRead()
            stream.append(buffer)

            val size = DispatchOnPacketCodec.peekFrameSize(stream)
            assertNull(size)
        }
    }

    // ──────────────────────── Insufficient data ────────────────────────

    @Test
    fun `peekFrameSize returns null when stream has no data`() {
        withStream { stream ->
            val size = DispatchOnPacketCodec.peekFrameSize(stream)
            assertNull(size)
        }
    }

    // ──────────────────────── Full StreamProcessor integration ────────────────────────

    @Test
    fun `full loop - peekFrameSize then decode`() {
        withStream { stream ->
            // Encode a ConnAck message
            val original = DispatchOnPacket.TypeConnAck(1u, 0u)
            val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
            DispatchOnPacketCodec.encode(buffer, original)
            buffer.resetForRead()
            stream.append(buffer)

            // Step 1: peek to determine frame size
            val frameSize = DispatchOnPacketCodec.peekFrameSize(stream)
            assertNotNull(frameSize)

            // Step 2: read exactly that many bytes and decode
            val decoded =
                stream.readBufferScoped(frameSize) {
                    DispatchOnPacketCodec.decode(this)
                }
            assertEquals(original, decoded)

            // Step 3: stream should be empty
            assertEquals(0, stream.available())
        }
    }

    @Test
    fun `full loop - multiple messages decoded sequentially`() {
        withStream { stream ->
            val msg1 = DispatchOnPacket.TypeConnect(4u, 120u)
            val msg2 = DispatchOnPacket.TypePubAck(999u)
            val msg3 = DispatchOnPacket.TypeConnAck(0u, 2u)

            // Encode all three into a single buffer
            val buffer = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
            DispatchOnPacketCodec.encode(buffer, msg1)
            DispatchOnPacketCodec.encode(buffer, msg2)
            DispatchOnPacketCodec.encode(buffer, msg3)
            buffer.resetForRead()
            stream.append(buffer)

            // Decode all three using peekFrameSize loop
            val decoded = mutableListOf<DispatchOnPacket>()
            while (stream.available() >= DispatchOnPacketCodec.MIN_HEADER_BYTES) {
                val frameSize = DispatchOnPacketCodec.peekFrameSize(stream) ?: break
                if (stream.available() < frameSize) break
                decoded.add(stream.readBufferScoped(frameSize) { DispatchOnPacketCodec.decode(this) })
            }

            assertEquals(3, decoded.size)
            assertEquals(msg1, decoded[0])
            assertEquals(msg2, decoded[1])
            assertEquals(msg3, decoded[2])
            assertEquals(0, stream.available())
        }
    }
}
