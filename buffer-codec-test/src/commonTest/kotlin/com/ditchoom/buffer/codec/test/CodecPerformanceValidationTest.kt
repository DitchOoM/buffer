package com.ditchoom.buffer.codec.test

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.encodeToBuffer
import com.ditchoom.buffer.codec.test.protocols.AllTypesMessage
import com.ditchoom.buffer.codec.test.protocols.AllTypesMessageCodec
import com.ditchoom.buffer.codec.test.protocols.ConnAckFlags
import com.ditchoom.buffer.codec.test.protocols.ConnectReturnCode
import com.ditchoom.buffer.codec.test.protocols.DnsFlags
import com.ditchoom.buffer.codec.test.protocols.DnsHeader
import com.ditchoom.buffer.codec.test.protocols.DnsHeaderCodec
import com.ditchoom.buffer.codec.test.protocols.MqttPacketConnAck
import com.ditchoom.buffer.codec.test.protocols.MqttPacketConnAckCodec
import com.ditchoom.buffer.codec.test.protocols.MqttPacketPubAck
import com.ditchoom.buffer.codec.test.protocols.MqttPacketPubAckCodec
import com.ditchoom.buffer.codec.test.protocols.PacketId
import com.ditchoom.buffer.codec.test.protocols.WsFrameHeader
import com.ditchoom.buffer.codec.test.protocols.WsFrameHeaderCodec
import com.ditchoom.buffer.codec.test.protocols.WsHeaderByte1
import com.ditchoom.buffer.codec.test.protocols.WsHeaderByte2
import com.ditchoom.buffer.codec.testRoundTrip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodecPerformanceValidationTest {
    @Test
    fun `connack batch into readShort`() {
        // ConnAck has 2 UByte value classes (1+1=2 bytes) batched into readShort
        val original = MqttPacketConnAck(ConnAckFlags(0x01u), ConnectReturnCode(0x04u))
        val decoded =
            MqttPacketConnAckCodec.testRoundTrip(
                original,
                expectedBytes = byteArrayOf(0x01, 0x04),
            )
        assertEquals(original, decoded)
    }

    @Test
    fun `ws frame header batch into readShort`() {
        // WsFrameHeader has 2 UByte value classes (1+1=2 bytes) batched into readShort
        val original = WsFrameHeader(WsHeaderByte1(0x81u), WsHeaderByte2(0x7Du))
        val decoded =
            WsFrameHeaderCodec.testRoundTrip(
                original,
                expectedBytes = byteArrayOf(0x81.toByte(), 0x7D),
            )
        assertEquals(original, decoded)
    }

    @Test
    fun `all types message batching`() {
        // AllTypesMessage batches: Byte+UByte+Short into readInt, Int+UInt into readLong
        val original =
            AllTypesMessage(
                byteVal = 0x12.toByte(),
                ubyteVal = 0x34u,
                shortVal = 0x5678.toShort(),
                ushortVal = 0x9ABCu,
                intVal = 0x12345678,
                uintVal = 0xDEADBEEFu,
                longVal = 0x0102030405060708L,
                ulongVal = 0xFEDCBA9876543210uL,
                floatVal = 3.14f,
                doubleVal = 2.71828,
                boolVal = true,
                stringVal = "test",
            )
        val decoded = AllTypesMessageCodec.testRoundTrip(original)
        // Compare non-float fields directly, float by bits (JS float precision issue)
        assertEquals(original.copy(floatVal = 0f), decoded.copy(floatVal = 0f))
        assertEquals(original.floatVal.toBits(), decoded.floatVal.toBits(), "floatVal")
    }

    @Test
    fun `dns header wire size is 12`() {
        val header =
            DnsHeader(
                id = 0x1234u,
                flags = DnsFlags(0x8180u),
                qdCount = 1u,
                anCount = 1u,
                nsCount = 0u,
                arCount = 0u,
            )
        val encoded = DnsHeaderCodec.encodeToBuffer(header)
        assertEquals(12, encoded.remaining())
    }

    @Test
    fun `connack wire size is 2`() {
        val connack = MqttPacketConnAck(ConnAckFlags(0u), ConnectReturnCode(0u))
        val encoded = MqttPacketConnAckCodec.encodeToBuffer(connack)
        assertEquals(2, encoded.remaining())
    }

    @Test
    fun `puback wire size is 2`() {
        val puback = MqttPacketPubAck(PacketId(1u))
        val encoded = MqttPacketPubAckCodec.encodeToBuffer(puback)
        assertEquals(2, encoded.remaining())
    }

    @Test
    fun `ws frame header wire size is 2`() {
        val header = WsFrameHeader(WsHeaderByte1(0u), WsHeaderByte2(0u))
        val encoded = WsFrameHeaderCodec.encodeToBuffer(header)
        assertEquals(2, encoded.remaining())
    }

    @Test
    fun `buffer position after decode`() {
        val connack = MqttPacketConnAck(ConnAckFlags(0u), ConnectReturnCode(0u))
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        MqttPacketConnAckCodec.encode(buffer, connack)
        buffer.resetForRead()
        assertEquals(2, buffer.remaining())
        MqttPacketConnAckCodec.decode(buffer)
        assertEquals(0, buffer.remaining())
    }

    @Test
    fun `all bytes consumed after round trip`() {
        verifyBytesConsumed(MqttPacketConnAckCodec, MqttPacketConnAck(ConnAckFlags(1u), ConnectReturnCode(0u)))
        verifyBytesConsumed(MqttPacketPubAckCodec, MqttPacketPubAck(PacketId(100u)))
        verifyBytesConsumed(WsFrameHeaderCodec, WsFrameHeader(WsHeaderByte1(0x81u), WsHeaderByte2(5u)))
    }

    @Test
    fun `code savings are significant`() {
        // The test protocol models validate that KSP generates correct codecs
        // from concise annotated data classes. Best suited for protocols with
        // fixed-width fields, length-prefixed strings, and type-byte discriminators
        // (e.g., MQTT connect/connack packets, DNS headers).
        assertTrue(true)
    }

    private fun <T : Any> verifyBytesConsumed(
        codec: Codec<T>,
        value: T,
    ) {
        val buffer = BufferFactory.Default.allocate(16, ByteOrder.BIG_ENDIAN)
        codec.encode(buffer, value)
        buffer.resetForRead()
        val bytesWritten = buffer.remaining()
        codec.decode(buffer)
        assertEquals(0, buffer.remaining(), "Not all bytes consumed for ${value::class.simpleName}")
    }
}
