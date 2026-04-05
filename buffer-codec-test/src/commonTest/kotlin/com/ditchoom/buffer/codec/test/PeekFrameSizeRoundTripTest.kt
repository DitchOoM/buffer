package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.test.protocols.ConnAckFlags
import com.ditchoom.buffer.codec.test.protocols.ConnectReturnCode
import com.ditchoom.buffer.codec.test.protocols.DispatchOnPacket
import com.ditchoom.buffer.codec.test.protocols.DispatchOnPacketCodec
import com.ditchoom.buffer.codec.test.protocols.DnsFlags
import com.ditchoom.buffer.codec.test.protocols.DnsHeader
import com.ditchoom.buffer.codec.test.protocols.DnsHeaderCodec
import com.ditchoom.buffer.codec.test.protocols.KeepAlive
import com.ditchoom.buffer.codec.test.protocols.MqttConnectFlags
import com.ditchoom.buffer.codec.test.protocols.MqttPacket
import com.ditchoom.buffer.codec.test.protocols.MqttPacketCodec
import com.ditchoom.buffer.codec.test.protocols.MqttPacketConnAck
import com.ditchoom.buffer.codec.test.protocols.MqttPacketConnect
import com.ditchoom.buffer.codec.test.protocols.MqttPacketConnectCodec
import com.ditchoom.buffer.codec.test.protocols.MqttPacketPubAck
import com.ditchoom.buffer.codec.test.protocols.PacketId
import com.ditchoom.buffer.codec.test.protocols.ProtocolLevel
import com.ditchoom.buffer.codec.test.protocols.SimpleHeader
import com.ditchoom.buffer.codec.test.protocols.SimpleHeaderCodec
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.FrameDetector
import com.ditchoom.buffer.stream.PeekResult
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runtime tests for generated peekFrameSize functions.
 * Validates that peekFrameSize returns correct byte counts when called
 * against a real StreamProcessor, not just that the code compiles.
 */
class PeekFrameSizeRoundTripTest {
    /** Calls the sync [FrameDetector.peekFrameSize] and converts [PeekResult] to [Int?]. */
    private fun FrameDetector.peekFrameSizeOrNull(stream: StreamProcessor, baseOffset: Int = 0): Int? =
        when (val result = peekFrameSize(stream, baseOffset)) {
            is PeekResult.Size -> result.bytes
            PeekResult.NeedsMoreData -> null
        }

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

            val size = DnsHeaderCodec.peekFrameSizeOrNull(stream)
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

            val size = SimpleHeaderCodec.peekFrameSizeOrNull(stream)
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

            val size = DispatchOnPacketCodec.peekFrameSizeOrNull(stream)
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

            val size = DispatchOnPacketCodec.peekFrameSizeOrNull(stream)
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

            val size = DispatchOnPacketCodec.peekFrameSizeOrNull(stream)
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

            val size = DispatchOnPacketCodec.peekFrameSizeOrNull(stream)
            assertNull(size)
        }
    }

    // ──────────────────────── Insufficient data ────────────────────────

    @Test
    fun `peekFrameSize returns null when stream has no data`() {
        withStream { stream ->
            val size = DispatchOnPacketCodec.peekFrameSizeOrNull(stream)
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
            val frameSize = DispatchOnPacketCodec.peekFrameSizeOrNull(stream)
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
                val frameSize = DispatchOnPacketCodec.peekFrameSizeOrNull(stream) ?: break
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

    // ──────────────── Variable-length: MQTT CONNECT with conditionals ────────────────

    @Test
    fun `variable-length MQTT CONNECT minimal`() {
        // Minimal CONNECT: no will, no username, no password
        val connect =
            MqttPacketConnect(
                protocolName = "MQTT",
                protocolLevel = ProtocolLevel(4u),
                connectFlags = MqttConnectFlags(0x00u), // all flags off
                keepAlive = KeepAlive(60u),
                clientId = "c1",
            )
        withStream { stream ->
            val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
            MqttPacketConnectCodec.encode(buffer, connect)
            val wireSize = buffer.position()
            buffer.resetForRead()
            stream.append(buffer)

            val peeked = MqttPacketConnectCodec.peekFrameSizeOrNull(stream)
            assertNotNull(peeked)
            assertEquals(wireSize, peeked)
        }
    }

    @Test
    fun `variable-length MQTT CONNECT with all conditional fields`() {
        // All flags set: will + username + password
        val flags = MqttConnectFlags(0xE4u) // username=1, password=1, willRetain=1, willQos=0, willFlag=1, cleanSession=0
        val connect =
            MqttPacketConnect(
                protocolName = "MQTT",
                protocolLevel = ProtocolLevel(4u),
                connectFlags = flags,
                keepAlive = KeepAlive(120u),
                clientId = "myClient",
                willTopic = "last/will",
                willMessage = "goodbye",
                username = "admin",
                password = "secret",
            )
        withStream { stream ->
            val buffer = BufferFactory.Default.allocate(512, ByteOrder.BIG_ENDIAN)
            MqttPacketConnectCodec.encode(buffer, connect)
            val wireSize = buffer.position()
            buffer.resetForRead()
            stream.append(buffer)

            val peeked = MqttPacketConnectCodec.peekFrameSizeOrNull(stream)
            assertNotNull(peeked)
            assertEquals(wireSize, peeked)
        }
    }

    @Test
    fun `variable-length MQTT CONNECT peekFrameSize then decode`() {
        // 0x84 = willFlag(1) + usernameFlag(1), no password
        val flags = MqttConnectFlags(0x84u)
        val connect =
            MqttPacketConnect(
                protocolName = "MQTT",
                protocolLevel = ProtocolLevel(4u),
                connectFlags = flags,
                keepAlive = KeepAlive(30u),
                clientId = "test",
                willTopic = "will/topic",
                willMessage = "msg",
                username = "user",
            )
        withStream { stream ->
            val buffer = BufferFactory.Default.allocate(512, ByteOrder.BIG_ENDIAN)
            MqttPacketConnectCodec.encode(buffer, connect)
            buffer.resetForRead()
            stream.append(buffer)

            val frameSize = MqttPacketConnectCodec.peekFrameSizeOrNull(stream)
            assertNotNull(frameSize)
            val decoded =
                stream.readBufferScoped(frameSize) {
                    MqttPacketConnectCodec.decode(this)
                }
            assertEquals(connect, decoded)
            assertEquals(0, stream.available())
        }
    }

    @Test
    fun `MQTT CONNECT peekFrameSize returns null with insufficient data`() {
        withStream { stream ->
            // Only write the length prefix of protocolName but not the string content
            val buffer = BufferFactory.Default.allocate(4, ByteOrder.BIG_ENDIAN)
            buffer.writeShort(100.toShort()) // claims 100 bytes of string content
            buffer.resetForRead()
            stream.append(buffer)

            val peeked = MqttPacketConnectCodec.peekFrameSizeOrNull(stream)
            assertNull(peeked) // not enough data
        }
    }

    // ──────────────── Sealed dispatch with variable-length variants ────────────────

    @Test
    fun `sealed dispatch peekFrameSize with variable-length variant`() {
        // MQTT CONNECT through the sealed dispatch — exercises both dispatch peek and variable-length peek
        val flags = MqttConnectFlags(0x02u) // cleanSession only
        val connect: MqttPacket =
            MqttPacketConnect(
                protocolName = "MQTT",
                protocolLevel = ProtocolLevel(4u),
                connectFlags = flags,
                keepAlive = KeepAlive(60u),
                clientId = "client-abc",
            )
        withStream { stream ->
            val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
            MqttPacketCodec.encode(buffer, connect)
            val wireSize = buffer.position()
            buffer.resetForRead()
            stream.append(buffer)

            val peeked = MqttPacketCodec.peekFrameSizeOrNull(stream)
            assertNotNull(peeked)
            assertEquals(wireSize, peeked)

            // Decode and verify
            val decoded =
                stream.readBufferScoped(peeked) {
                    MqttPacketCodec.decode(this)
                }
            assertTrue(decoded is MqttPacketConnect)
            assertEquals(connect, decoded)
        }
    }

    @Test
    fun `sealed dispatch peek then decode mixed fixed and variable messages`() {
        // Mix of fixed-size (ConnAck, PubAck) and variable-size (Connect) packets
        val msg1: MqttPacket =
            MqttPacketConnect(
                protocolName = "MQTT",
                protocolLevel = ProtocolLevel(4u),
                connectFlags = MqttConnectFlags(0x02u),
                keepAlive = KeepAlive(60u),
                clientId = "c1",
            )
        val msg2: MqttPacket = MqttPacketConnAck(ConnAckFlags(0u), ConnectReturnCode(0u))
        val msg3: MqttPacket = MqttPacketPubAck(PacketId(42u))

        withStream { stream ->
            val buffer = BufferFactory.Default.allocate(256, ByteOrder.BIG_ENDIAN)
            MqttPacketCodec.encode(buffer, msg1)
            MqttPacketCodec.encode(buffer, msg2)
            MqttPacketCodec.encode(buffer, msg3)
            buffer.resetForRead()
            stream.append(buffer)

            val decoded = mutableListOf<MqttPacket>()
            while (stream.available() >= MqttPacketCodec.MIN_HEADER_BYTES) {
                val frameSize = MqttPacketCodec.peekFrameSizeOrNull(stream) ?: break
                if (stream.available() < frameSize) break
                decoded.add(stream.readBufferScoped(frameSize) { MqttPacketCodec.decode(this) })
            }

            assertEquals(3, decoded.size)
            assertEquals(msg1, decoded[0])
            assertEquals(msg2, decoded[1])
            assertEquals(msg3, decoded[2])
            assertEquals(0, stream.available())
        }
    }

    @Test
    fun `fixed-size codec peekFrameSize returns constant even on empty stream`() {
        // Fixed-size codecs return the constant frame size regardless of stream state.
        // The caller is responsible for checking stream.available() >= frameSize.
        withStream { stream ->
            val size = SimpleHeaderCodec.peekFrameSizeOrNull(stream)
            assertEquals(7, size)
        }
    }
}
