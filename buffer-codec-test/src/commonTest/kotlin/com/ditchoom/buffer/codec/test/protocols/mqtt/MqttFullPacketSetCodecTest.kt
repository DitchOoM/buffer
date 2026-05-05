package com.ditchoom.buffer.codec.test.protocols.mqtt

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImage
import com.ditchoom.buffer.codec.test.protocols.payload.JpegImageCodec
import com.ditchoom.buffer.codec.test.protocols.payload.PacketId
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase J.M step 7 — end-to-end byte-exact regression suite for
 * the full v3.1.1 sealed `MqttPacket` family.
 *
 * Drives every variant through three assertions routed via the
 * sealed-root dispatcher (`MqttPacketCodec.decode` /
 * `peekFrameSize`) rather than the per-variant codec object:
 *
 *  1. **Round-trip equality.** Encode through `MqttPacketCodec`,
 *     decode through `MqttPacketCodec`, assert the decoded value
 *     equals the original. Confirms the dispatcher's peek-then-
 *     route path correctly identifies the variant from the fixed
 *     header byte and delegates to the matching variant codec.
 *  2. **`peekFrameSize` Complete on a fully-buffered packet.** A
 *     stream containing the entire encoded packet returns
 *     `Complete(totalBytes)` from the dispatcher's peek path —
 *     no consumption, exact byte count.
 *  3. **Drip-fed `peekFrameSize`.** Append one byte at a time;
 *     every intermediate state returns `NeedsMoreData`; only the
 *     final byte triggers `Complete(totalBytes)`. Confirms the
 *     dispatcher's peek correctly composes with each variant's
 *     peek for the var-int + body progression.
 *
 * Per-variant tests (rather than a parametrized loop) so a
 * regression in any single variant points directly at the failing
 * variant's wire shape rather than a collapsed loop iteration.
 *
 * Payload-bearing variant (`Publish<P>`) uses `JpegImageCodec`;
 * `MqttPacket<Nothing>` variants are assignable through any
 * `MqttPacket<P>` instantiation by covariance, so they all route
 * through the same `MqttPacketCodec(JpegImageCodec)` dispatcher.
 */
class MqttFullPacketSetCodecTest {
    private val dispatcher = MqttPacketCodec(JpegImageCodec)

    @Test
    fun connectRoundTripsThroughDispatcher() {
        val original =
            MqttPacket.Connect(
                header = MqttFixedHeader(0x10u),
                // body = 6 (proto LP) + 1 (level) + 1 (flags) + 2 (keepalive) +
                //        2 + 4 (clientId LP "abcd") = 16
                remainingLength = 16u,
                protocolName = "MQTT",
                protocolLevel = 0x04u,
                connectFlags = MqttConnectFlags(0x02u),
                keepAliveSeconds = 60u,
                clientId = "abcd",
            )
        assertDispatcherRoundTrip(original, expectedTotalBytes = 18)
    }

    @Test
    fun connAckRoundTripsThroughDispatcher() {
        val original =
            MqttPacket.ConnAck(
                connectAckFlags = 0x01u,
                returnCode = 0x00u,
            )
        assertDispatcherRoundTrip(original, expectedTotalBytes = 4)
    }

    @Test
    fun publishQos0RoundTripsThroughDispatcher() {
        // QoS=0 header byte 0x30 — packetId omitted from the wire.
        // body = 2 (topic LP) + 3 (topic) + 4 + 4 (jpeg) = 13
        val original =
            MqttPacket.Publish(
                header = MqttFixedHeader(0x30u),
                remainingLength = 13u,
                topic = "t/1",
                packetId = null,
                payload = JpegImage(2u, 3u, byteArrayOf(0x11, 0x22, 0x33, 0x44)),
            )
        assertDispatcherRoundTrip(original, expectedTotalBytes = 15)
    }

    @Test
    fun publishQos1RoundTripsThroughDispatcher() {
        // QoS=1 header byte 0x32 — packetId on the wire.
        // body = 2 + 3 (topic) + 2 (pid) + 4 + 4 (jpeg) = 15
        val original =
            MqttPacket.Publish(
                header = MqttFixedHeader(0x32u),
                remainingLength = 15u,
                topic = "t/1",
                packetId = PacketId(0x002Au),
                payload = JpegImage(2u, 3u, byteArrayOf(0x11, 0x22, 0x33, 0x44)),
            )
        assertDispatcherRoundTrip(original, expectedTotalBytes = 17)
    }

    @Test
    fun pubAckRoundTripsThroughDispatcher() {
        assertDispatcherRoundTrip(MqttPacket.PubAck(packetIdentifier = 0x1234u), expectedTotalBytes = 4)
    }

    @Test
    fun pubRecRoundTripsThroughDispatcher() {
        assertDispatcherRoundTrip(MqttPacket.PubRec(packetIdentifier = 0x1234u), expectedTotalBytes = 4)
    }

    @Test
    fun pubRelRoundTripsThroughDispatcher() {
        assertDispatcherRoundTrip(MqttPacket.PubRel(packetIdentifier = 0x1234u), expectedTotalBytes = 4)
    }

    @Test
    fun pubCompRoundTripsThroughDispatcher() {
        assertDispatcherRoundTrip(MqttPacket.PubComp(packetIdentifier = 0x1234u), expectedTotalBytes = 4)
    }

    @Test
    fun subscribeRoundTripsThroughDispatcher() {
        // body = 2 (pid) + 2 + 3 (topic LP "t/1") + 1 (qos) = 8
        val original =
            MqttPacket.Subscribe(
                remainingLength = 8u,
                packetIdentifier = 0x000Au,
                topicFilters = listOf(MqttTopicFilter(filter = "t/1", qos = 0x01u)),
            )
        assertDispatcherRoundTrip(original, expectedTotalBytes = 10)
    }

    @Test
    fun subAckRoundTripsThroughDispatcher() {
        // body = 2 (pid) + 3 (return codes) = 5
        val original =
            MqttPacket.SubAck(
                remainingLength = 5u,
                packetIdentifier = 0x000Au,
                returnCodes = listOf(0x00u, 0x01u, 0x80u),
            )
        assertDispatcherRoundTrip(original, expectedTotalBytes = 7)
    }

    @Test
    fun unsubscribeRoundTripsThroughDispatcher() {
        // body = 2 (pid) + 2 + 3 (topic LP "t/1") = 7
        val original =
            MqttPacket.Unsubscribe(
                remainingLength = 7u,
                packetIdentifier = 0x000Au,
                topics = listOf(MqttUnsubscribeTopic("t/1")),
            )
        assertDispatcherRoundTrip(original, expectedTotalBytes = 9)
    }

    @Test
    fun unsubAckRoundTripsThroughDispatcher() {
        assertDispatcherRoundTrip(MqttPacket.UnsubAck(packetIdentifier = 0x1234u), expectedTotalBytes = 4)
    }

    @Test
    fun pingReqRoundTripsThroughDispatcher() {
        assertDispatcherRoundTrip(MqttPacket.PingReq(), expectedTotalBytes = 2)
    }

    @Test
    fun pingRespRoundTripsThroughDispatcher() {
        assertDispatcherRoundTrip(MqttPacket.PingResp(), expectedTotalBytes = 2)
    }

    @Test
    fun disconnectRoundTripsThroughDispatcher() {
        assertDispatcherRoundTrip(MqttPacket.Disconnect(), expectedTotalBytes = 2)
    }

    private fun assertDispatcherRoundTrip(
        original: MqttPacket<JpegImage>,
        expectedTotalBytes: Int,
    ) {
        val encoded = encodeViaDispatcher(original)
        encoded.resetForRead()
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
            fullStream.append(encodeViaDispatcher(original).also { it.resetForRead() })
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
            val dripSource = encodeViaDispatcher(original).also { it.resetForRead() }
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

    private fun encodeViaDispatcher(value: MqttPacket<JpegImage>) =
        BufferFactory.Default
            .allocate(128, ByteOrder.BIG_ENDIAN)
            .also { dispatcher.encode(it, value, EncodeContext.Empty) }
}
