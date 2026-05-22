package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttUnsubscribeTopic
import com.ditchoom.buffer.codec.test.protocols.mqttv5.suback.V5SubAckReasonCode
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImage
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImageCodec
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * SUBSCRIBE / SUBACK / UNSUBSCRIBE v5. Each
 * variant composes the always-present property bag ( shape)
 * with a `@RemainingBytes List<...>` tail ( shape):
 *
 *   - SUBSCRIBE:    `@RemainingBytes List<V5Subscription>`
 *   - SUBACK:       `@RemainingBytes List<UByte>` (reason codes)
 *   - UNSUBSCRIBE:  `@RemainingBytes List<MqttUnsubscribeTopic>`
 *
 * Verifies: the inner property-bag bound is restored before the
 * trailing list reads, and the list reads to the outer RL bound.
 */
class MqttV5SubscribeFamilyCodecTest {
    @Test
    fun encodesSubscribeByteExact() {
        // body = 2 (pid) + 1 (propLen=0) + 5 (topic LP "t/1": 2+3) + 1 (opts) = 9
        val msg =
            MqttV5Packet.Subscribe(
                packetIdentifier = 0x002Au,
                properties = V5PropertyBag.EMPTY,
                topicFilters =
                    listOf(
                        V5Subscription(
                            topicFilter = "t/1",
                            subscriptionOptions = V5SubscriptionOptions.of(qos = 1),
                        ),
                    ),
            )
        val buf = encode(msg)
        val expected =
            byteArrayOf(
                0x82.toByte(),
                0x09, // RL = 9
                0x00,
                0x2A, // pid
                0x00, // propLen = 0
                0x00,
                0x03,
                't'.code.toByte(),
                '/'.code.toByte(),
                '1'.code.toByte(), // topic LP
                0x01, // subscription options (QoS=1)
            )
        assertContentEquals(expected, buf.readByteArray(buf.remaining()))
    }

    @Test
    fun roundTripsSubscribeWithMultipleFiltersAndProperties() {
        val original =
            MqttV5Packet.Subscribe(
                // body = 2 (pid) + 1 (propLen=5) + 5 (MessageExpiry)
                //      + 6 (LP "t/1": 2+3 + opts: 1) + 6 (LP "t/2" + opts) = 20
                packetIdentifier = 0x0042u,
                properties = V5PropertyBag.of(MqttV5Property.MessageExpiryInterval(seconds = 3_600u)),
                topicFilters =
                    listOf(
                        V5Subscription(
                            topicFilter = "t/1",
                            subscriptionOptions = V5SubscriptionOptions.of(qos = 0),
                        ),
                        V5Subscription(
                            topicFilter = "t/2",
                            subscriptionOptions = V5SubscriptionOptions.of(qos = 2),
                        ),
                    ),
            )
        val buf = encode(original)
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun encodesSubAckByteExact() {
        // body = 2 (pid) + 1 (propLen=0) + 1 (rc) = 4
        val msg =
            MqttV5Packet.SubAck(
                packetIdentifier = 0x002Au,
                properties = V5PropertyBag.EMPTY,
                reasonCodes = listOf(V5SubAckReasonCode.GrantedQoS1()),
            )
        val buf = encode(msg)
        assertContentEquals(
            byteArrayOf(0x90.toByte(), 0x04, 0x00, 0x2A, 0x00, 0x01),
            buf.readByteArray(buf.remaining()),
        )
    }

    @Test
    fun roundTripsSubAckWithMultipleReasonCodesAndProperties() {
        val original =
            MqttV5Packet.SubAck(
                // body = 2 + 1 (propLen=5) + 5 (MessageExpiry) + 3 (rc list) = 11
                packetIdentifier = 0x0042u,
                properties = V5PropertyBag.of(MqttV5Property.MessageExpiryInterval(seconds = 60u)),
                reasonCodes =
                    listOf(
                        V5SubAckReasonCode.GrantedQoS0(),
                        V5SubAckReasonCode.GrantedQoS1(),
                        V5SubAckReasonCode.GrantedQoS2(),
                    ),
            )
        val buf = encode(original)
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripsUnsubscribeWithProperties() {
        val original =
            MqttV5Packet.Unsubscribe(
                // body = 2 (pid) + 1 (propLen=0) + 5 (LP "t/1": 2+3) + 5 (LP "t/2") = 13
                packetIdentifier = 0x0042u,
                properties = V5PropertyBag.EMPTY,
                topics =
                    listOf(
                        MqttUnsubscribeTopic(name = "t/1"),
                        MqttUnsubscribeTopic(name = "t/2"),
                    ),
            )
        val buf = encode(original)
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun decodesSubscribeFromSpecBytes() {
        // body = 2 (pid) + 1 (propLen=0) + 7 (LP "topic": 2+5) + 1 (opts) = 11
        val wire =
            byteArrayOf(
                0x82.toByte(),
                0x0B, // RL = 11
                0x00,
                0x07, // pid = 7
                0x00, // propLen = 0
                0x00,
                0x05,
                't'.code.toByte(),
                'o'.code.toByte(),
                'p'.code.toByte(),
                'i'.code.toByte(),
                'c'.code.toByte(),
                0x02, // qos = 2
            )
        val buf =
            BufferFactory.Default
                .allocate(wire.size, ByteOrder.BIG_ENDIAN)
                .also {
                    it.writeBytes(wire)
                    it.resetForRead()
                }
        val decoded = jpegDispatcher().decode(buf, DecodeContext.Empty)
        val sub = assertIs<MqttV5Packet.Subscribe>(decoded)
        assertEquals(0x0007u.toUShort(), sub.packetIdentifier)
        assertEquals(V5PropertyBag.EMPTY, sub.properties)
        assertEquals(1, sub.topicFilters.size)
        assertEquals("topic", sub.topicFilters[0].topicFilter)
        assertEquals(V5SubscriptionOptions.of(qos = 2), sub.topicFilters[0].subscriptionOptions)
    }

    @Test
    fun peekFrameSizeForSubscribeCompletes() {
        val msg =
            MqttV5Packet.Subscribe(
                packetIdentifier = 0x0001u,
                properties = V5PropertyBag.EMPTY,
                topicFilters = listOf(V5Subscription("t/1", V5SubscriptionOptions.of(qos = 0))),
            )
        val buf = encode(msg)
        val totalBytes = buf.remaining()

        val pool = BufferPool()
        val stream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            stream.append(buf)
            assertEquals(PeekResult.Complete(totalBytes), jpegDispatcher().peekFrameSize(stream))
        } finally {
            stream.release()
            pool.clear()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun encode(value: MqttV5Packet<*>): ReadBuffer =
        jpegDispatcher().encode(value as MqttV5Packet<JpegImage>, EncodeContext.Empty, BufferFactory.Default)

    private fun jpegDispatcher(): MqttV5PacketCodec<JpegImage> = MqttV5PacketCodec(JpegImageCodec)
}
