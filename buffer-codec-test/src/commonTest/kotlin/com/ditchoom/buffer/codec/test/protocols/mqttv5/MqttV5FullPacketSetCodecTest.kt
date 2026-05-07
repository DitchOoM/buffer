package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttConnectFlags
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttUnsubscribeTopic
import com.ditchoom.buffer.codec.test.protocols.mqttv5.connack.V5ConnectReasonCode
import com.ditchoom.buffer.codec.test.protocols.mqttv5.puback.V5PubAckReasonCode
import com.ditchoom.buffer.codec.test.protocols.mqttv5.suback.V5SubAckReasonCode
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImage
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImageCodec
import com.ditchoom.buffer.codec.test.protocols.payload.PacketId
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase J.M.5 slice 8 — end-to-end byte-exact regression suite for the
 * full v5.0 sealed [MqttV5Packet] family. Mirrors the v3 doctrine
 * vector at
 * `:buffer-codec-test/.../mqtt/MqttFullPacketSetCodecTest.kt` (Phase
 * J.M step 7) — drives every v5 variant through three assertions
 * routed via the sealed-root dispatcher:
 *
 *  1. **Round-trip equality** — encode through `MqttV5PacketCodec`,
 *     decode through it, assert the decoded value equals the original.
 *  2. **`peekFrameSize` Complete on a fully-buffered packet** — a
 *     stream containing the entire encoded packet returns
 *     `Complete(totalBytes)` from the dispatcher's peek path.
 *  3. **Drip-fed `peekFrameSize`** — append one byte at a time; every
 *     intermediate state returns `NeedsMoreData`; only the final byte
 *     triggers `Complete(totalBytes)`.
 *
 * Per-variant tests (not a parametrized loop) so a regression in any
 * single variant points directly at the failing variant's wire shape.
 *
 * Note: cascading-trailer acks (PubAck et al.) collapse peek to
 * NoFraming when their grammar-2 conditional fields are unbounded
 * upstream — but each ack carries an upstream `@UseCodec(MqttRemaining
 * LengthCodec)` bounding RL, so peek delegates to the bounding-codec
 * walker (slice 6) and `Complete(total)` is reachable.
 */
class MqttV5FullPacketSetCodecTest {
    private val dispatcher = MqttV5PacketCodec(JpegImageCodec)

    @Test
    fun connectRoundTripsThroughDispatcher() {
        val original =
            MqttV5Packet.Connect(
                protocolName = "MQTT",
                protocolLevel = 0x05u,
                connectFlags = MqttConnectFlags(0x02u),
                keepAliveSeconds = 60u,
                properties = V5PropertyBag.EMPTY,
                clientId = "abc",
            )
        assertDispatcherRoundTrip(original, expectedTotalBytes = 18)
    }

    @Test
    fun connAckRoundTripsThroughDispatcher() {
        val original =
            MqttV5Packet.ConnAck(
                connectAckFlags = 0x01u,
                reasonCode = V5ConnectReasonCode.Success(),
                properties = V5PropertyBag.EMPTY,
            )
        assertDispatcherRoundTrip(original, expectedTotalBytes = 5)
    }

    @Test
    fun publishQos0RoundTripsThroughDispatcher() {
        val original =
            MqttV5Packet.Publish(
                topic = "t/1",
                packetId = null,
                properties = V5PropertyBag.EMPTY,
                payload = JpegImage(1u, 1u, byteArrayOf(0x42)),
            )
        assertDispatcherRoundTrip(original, expectedTotalBytes = 13)
    }

    @Test
    fun publishQos1RoundTripsThroughDispatcher() {
        val original =
            MqttV5Packet.Publish(
                header = MqttFixedHeader(0x32u),
                topic = "t/1",
                packetId = PacketId(0x002Au),
                properties = V5PropertyBag.EMPTY,
                payload = JpegImage(1u, 1u, byteArrayOf(0x42)),
            )
        assertDispatcherRoundTrip(original, expectedTotalBytes = 15)
    }

    @Test
    fun pubAckRoundTripsThroughDispatcher() {
        assertDispatcherRoundTrip(
            MqttV5Packet.PubAck(packetIdentifier = 0x1234u),
            expectedTotalBytes = 4,
        )
    }

    @Test
    fun pubAckWithCascadingTrailersRoundTripsThroughDispatcher() {
        val original =
            MqttV5Packet.PubAck(
                packetIdentifier = 0x1234u,
                reasonCode = V5PubAckReasonCode.Success(),
                properties = V5PropertyBag.of(MqttV5Property.MessageExpiryInterval(seconds = 60u)),
            )
        assertDispatcherRoundTrip(original, expectedTotalBytes = 11)
    }

    @Test
    fun pubRecRoundTripsThroughDispatcher() {
        assertDispatcherRoundTrip(
            MqttV5Packet.PubRec(packetIdentifier = 0x1234u),
            expectedTotalBytes = 4,
        )
    }

    @Test
    fun pubRelRoundTripsThroughDispatcher() {
        assertDispatcherRoundTrip(
            MqttV5Packet.PubRel(packetIdentifier = 0x1234u),
            expectedTotalBytes = 4,
        )
    }

    @Test
    fun pubCompRoundTripsThroughDispatcher() {
        assertDispatcherRoundTrip(
            MqttV5Packet.PubComp(packetIdentifier = 0x1234u),
            expectedTotalBytes = 4,
        )
    }

    @Test
    fun subscribeRoundTripsThroughDispatcher() {
        val original =
            MqttV5Packet.Subscribe(
                packetIdentifier = 0x000Au,
                properties = V5PropertyBag.EMPTY,
                topicFilters = listOf(V5Subscription("t/1", V5SubscriptionOptions.of(qos = 1))),
            )
        assertDispatcherRoundTrip(original, expectedTotalBytes = 11)
    }

    @Test
    fun subAckRoundTripsThroughDispatcher() {
        val original =
            MqttV5Packet.SubAck(
                packetIdentifier = 0x000Au,
                properties = V5PropertyBag.EMPTY,
                reasonCodes =
                    listOf(
                        V5SubAckReasonCode.GrantedQoS0(),
                        V5SubAckReasonCode.GrantedQoS1(),
                        V5SubAckReasonCode.UnspecifiedError(),
                    ),
            )
        assertDispatcherRoundTrip(original, expectedTotalBytes = 8)
    }

    @Test
    fun unsubscribeRoundTripsThroughDispatcher() {
        val original =
            MqttV5Packet.Unsubscribe(
                packetIdentifier = 0x000Au,
                properties = V5PropertyBag.EMPTY,
                topics = listOf(MqttUnsubscribeTopic("t/1")),
            )
        assertDispatcherRoundTrip(original, expectedTotalBytes = 10)
    }

    @Test
    fun unsubAckRoundTripsThroughDispatcher() {
        assertDispatcherRoundTrip(
            MqttV5Packet.UnsubAck(packetIdentifier = 0x1234u),
            expectedTotalBytes = 4,
        )
    }

    @Test
    fun pingReqRoundTripsThroughDispatcher() {
        assertDispatcherRoundTrip(MqttV5Packet.PingReq(), expectedTotalBytes = 2)
    }

    @Test
    fun pingRespRoundTripsThroughDispatcher() {
        assertDispatcherRoundTrip(MqttV5Packet.PingResp(), expectedTotalBytes = 2)
    }

    @Test
    fun disconnectRoundTripsThroughDispatcher() {
        assertDispatcherRoundTrip(MqttV5Packet.Disconnect(), expectedTotalBytes = 2)
    }

    @Test
    fun authRoundTripsThroughDispatcher() {
        assertDispatcherRoundTrip(MqttV5Packet.Auth(), expectedTotalBytes = 2)
    }

    private fun assertDispatcherRoundTrip(
        original: MqttV5Packet<JpegImage>,
        expectedTotalBytes: Int,
    ) {
        val encoded = encodeViaDispatcher(original)
        assertEquals(
            expectedTotalBytes,
            encoded.remaining(),
            "wire length matches expected byte count for ${original::class.simpleName}",
        )

        // (1) Round-trip equality.
        val decoded = dispatcher.decode(encoded, DecodeContext.Empty)
        assertEquals(original, decoded, "dispatcher round-trip equality for ${original::class.simpleName}")
        assertEquals(0, encoded.remaining(), "decode consumed every byte")

        // (2) peekFrameSize Complete on the fully-buffered packet.
        val pool = BufferPool()
        val fullStream = StreamProcessor.create(pool, ByteOrder.BIG_ENDIAN)
        try {
            fullStream.append(encodeViaDispatcher(original))
            assertEquals(
                PeekResult.Complete(expectedTotalBytes),
                dispatcher.peekFrameSize(fullStream),
                "fully-buffered peekFrameSize Complete for ${original::class.simpleName}",
            )
        } finally {
            fullStream.release()
            pool.clear()
        }

        // (3) Drip-fed peekFrameSize → NeedsMoreData until the final byte.
        val dripPool = BufferPool()
        val dripStream = StreamProcessor.create(dripPool, ByteOrder.BIG_ENDIAN)
        try {
            val dripSource = encodeViaDispatcher(original)
            for (i in 0 until expectedTotalBytes - 1) {
                val one = BufferFactory.Default.allocate(1)
                one.writeByte(dripSource.readByte())
                one.resetForRead()
                dripStream.append(one)
                assertEquals(
                    PeekResult.NeedsMoreData,
                    dispatcher.peekFrameSize(dripStream),
                    "${original::class.simpleName} after ${i + 1} of $expectedTotalBytes bytes",
                )
            }
            val last = BufferFactory.Default.allocate(1)
            last.writeByte(dripSource.readByte())
            last.resetForRead()
            dripStream.append(last)
            assertEquals(
                PeekResult.Complete(expectedTotalBytes),
                dispatcher.peekFrameSize(dripStream),
                "${original::class.simpleName} final byte completes the frame",
            )
        } finally {
            dripStream.release()
            dripPool.clear()
        }
    }

    private fun encodeViaDispatcher(value: MqttV5Packet<JpegImage>): ReadBuffer =
        dispatcher.encode(value, EncodeContext.Empty, BufferFactory.Default)
}
