package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.test.protocols.payload.BinaryData
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Phase J.M.5 slice 15f — [V5PropertyBag] / [V5PropertyBagCodec] direct
 * tests. Closes the cross-property-uniqueness gap that v5_audit_gaps.md
 * tracked as the deferred slice 15e (renamed slice 15f after 15e was
 * reused for `@RemainingBytes String`).
 *
 * Coverage:
 *
 *  - Empty bag round-trip (single 0x00 VBI prefix on the wire).
 *  - Mixed-id wire decodes route into typed slots regardless of arrival
 *    order.
 *  - Duplicate unique-cardinality property → [DecodeException]
 *    (Protocol Error per spec §2.2.2).
 *  - Repeats of [MqttV5Property.UserProperty] (§3.1.2.11.8) and
 *    [MqttV5Property.SubscriptionIdentifier] (§3.3.2.3.8) are accepted
 *    and accumulate in wire order.
 *  - [List.toV5PropertyBag] applies the same uniqueness invariant at
 *    construction time (mirror of decode).
 */
class V5PropertyBagCodecTest {
    @Test
    fun emptyBagEncodesAsSingleZeroVbi() {
        val buf = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        V5PropertyBagCodec.encode(buf, V5PropertyBag.EMPTY, EncodeContext.Empty)
        buf.resetForRead()
        val written = buf.readByteArray(buf.remaining())
        assertContentEquals(byteArrayOf(0x00), written)
    }

    @Test
    fun emptyBagRoundTrips() {
        val buf = BufferFactory.Default.allocate(8, ByteOrder.BIG_ENDIAN)
        V5PropertyBagCodec.encode(buf, V5PropertyBag.EMPTY, EncodeContext.Empty)
        buf.resetForRead()
        val decoded = V5PropertyBagCodec.decode(buf, DecodeContext.Empty)
        assertSame(V5PropertyBag.EMPTY, decoded)
    }

    @Test
    fun roundTripsBagWithEveryUniqueVariantPopulated() {
        val original =
            V5PropertyBag(
                payloadFormatIndicator = MqttV5Property.PayloadFormatIndicator(value = 1u),
                messageExpiryInterval = MqttV5Property.MessageExpiryInterval(seconds = 600u),
                contentType = MqttV5Property.ContentType(value = "text/plain"),
                responseTopic = MqttV5Property.ResponseTopic(value = "r/topic"),
                correlationData =
                    MqttV5Property.CorrelationData(
                        data = BinaryData(byteArrayOf(0x01, 0x02, 0x03)),
                    ),
                sessionExpiryInterval = MqttV5Property.SessionExpiryInterval(seconds = 300u),
                assignedClientIdentifier = MqttV5Property.AssignedClientIdentifier(value = "cid-9"),
                serverKeepAlive = MqttV5Property.ServerKeepAlive(seconds = 30u),
                authenticationMethod = MqttV5Property.AuthenticationMethod(value = "SCRAM-SHA-256"),
                authenticationData =
                    MqttV5Property.AuthenticationData(
                        data = BinaryData(byteArrayOf(0x10, 0x20)),
                    ),
                requestProblemInformation = MqttV5Property.RequestProblemInformation(value = 1u),
                willDelayInterval = MqttV5Property.WillDelayInterval(seconds = 5u),
                requestResponseInformation = MqttV5Property.RequestResponseInformation(value = 0u),
                responseInformation = MqttV5Property.ResponseInformation(value = "resp-info"),
                serverReference = MqttV5Property.ServerReference(value = "alt.example"),
                reasonString = MqttV5Property.ReasonString(value = "ok"),
                receiveMaximum = MqttV5Property.ReceiveMaximum(value = 100u),
                topicAliasMaximum = MqttV5Property.TopicAliasMaximum(value = 65_535u),
                topicAlias = MqttV5Property.TopicAlias(value = 7u),
                maximumQoS = MqttV5Property.MaximumQoS(value = 1u),
                retainAvailable = MqttV5Property.RetainAvailable(value = 1u),
                maximumPacketSize = MqttV5Property.MaximumPacketSize(value = 65_536u),
                wildcardSubscriptionAvailable = MqttV5Property.WildcardSubscriptionAvailable(value = 1u),
                subscriptionIdentifiersAvailable = MqttV5Property.SubscriptionIdentifiersAvailable(value = 1u),
                sharedSubscriptionAvailable = MqttV5Property.SharedSubscriptionAvailable(value = 0u),
            )
        val buf = BufferFactory.Default.allocate(512, ByteOrder.BIG_ENDIAN)
        V5PropertyBagCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        val decoded = V5PropertyBagCodec.decode(buf, DecodeContext.Empty)
        // Binary-data fields use ByteArray reference equality; compare bytes
        // directly. The remaining unique-cardinality fields are data-class-
        // equal, so a single full-bag equality check covers them. Use
        // explicit field comparisons here for readability.
        assertEquals(original.payloadFormatIndicator, decoded.payloadFormatIndicator)
        assertEquals(original.messageExpiryInterval, decoded.messageExpiryInterval)
        assertEquals(original.contentType, decoded.contentType)
        assertEquals(original.responseTopic, decoded.responseTopic)
        assertContentEquals(
            original.correlationData!!.data.bytes,
            decoded.correlationData!!.data.bytes,
        )
        assertEquals(original.sessionExpiryInterval, decoded.sessionExpiryInterval)
        assertEquals(original.assignedClientIdentifier, decoded.assignedClientIdentifier)
        assertEquals(original.serverKeepAlive, decoded.serverKeepAlive)
        assertEquals(original.authenticationMethod, decoded.authenticationMethod)
        assertContentEquals(
            original.authenticationData!!.data.bytes,
            decoded.authenticationData!!.data.bytes,
        )
        assertEquals(original.requestProblemInformation, decoded.requestProblemInformation)
        assertEquals(original.willDelayInterval, decoded.willDelayInterval)
        assertEquals(original.requestResponseInformation, decoded.requestResponseInformation)
        assertEquals(original.responseInformation, decoded.responseInformation)
        assertEquals(original.serverReference, decoded.serverReference)
        assertEquals(original.reasonString, decoded.reasonString)
        assertEquals(original.receiveMaximum, decoded.receiveMaximum)
        assertEquals(original.topicAliasMaximum, decoded.topicAliasMaximum)
        assertEquals(original.topicAlias, decoded.topicAlias)
        assertEquals(original.maximumQoS, decoded.maximumQoS)
        assertEquals(original.retainAvailable, decoded.retainAvailable)
        assertEquals(original.maximumPacketSize, decoded.maximumPacketSize)
        assertEquals(original.wildcardSubscriptionAvailable, decoded.wildcardSubscriptionAvailable)
        assertEquals(original.subscriptionIdentifiersAvailable, decoded.subscriptionIdentifiersAvailable)
        assertEquals(original.sharedSubscriptionAvailable, decoded.sharedSubscriptionAvailable)
    }

    @Test
    fun decodeRoutesPropertiesIntoTypedSlotsRegardlessOfWireOrder() {
        // Wire order: ContentType (id 0x03) followed by SessionExpiryInterval
        // (id 0x11) followed by ReasonString (id 0x1F) — three different ids
        // in non-ascending arrival sequence. Decoder must place each into
        // its typed slot without sensitivity to wire order.
        val original =
            V5PropertyBag(
                contentType = MqttV5Property.ContentType(value = "text/plain"),
                sessionExpiryInterval = MqttV5Property.SessionExpiryInterval(seconds = 60u),
                reasonString = MqttV5Property.ReasonString(value = "ok"),
            )
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        V5PropertyBagCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        val decoded = V5PropertyBagCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original, decoded)
    }

    @Test
    fun userPropertyAccumulatesInWireOrderAndDoesNotRejectRepeats() {
        val original =
            V5PropertyBag(
                userProperties =
                    listOf(
                        MqttV5Property.UserProperty(key = "a", value = "1"),
                        MqttV5Property.UserProperty(key = "b", value = "2"),
                        MqttV5Property.UserProperty(key = "a", value = "3"), // same key, different value — both kept
                    ),
            )
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        V5PropertyBagCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        val decoded = V5PropertyBagCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original.userProperties, decoded.userProperties)
        // Order is significant per §3.1.2.11.8 — verify positionally.
        assertEquals("a", decoded.userProperties[0].key)
        assertEquals("1", decoded.userProperties[0].value)
        assertEquals("b", decoded.userProperties[1].key)
        assertEquals("a", decoded.userProperties[2].key)
        assertEquals("3", decoded.userProperties[2].value)
    }

    @Test
    fun subscriptionIdentifierAccumulatesAndDoesNotRejectRepeats() {
        val original =
            V5PropertyBag(
                subscriptionIdentifiers =
                    listOf(
                        MqttV5Property.SubscriptionIdentifier(value = 1u),
                        MqttV5Property.SubscriptionIdentifier(value = 17u),
                        MqttV5Property.SubscriptionIdentifier(value = 9_999u),
                    ),
            )
        val buf = BufferFactory.Default.allocate(64, ByteOrder.BIG_ENDIAN)
        V5PropertyBagCodec.encode(buf, original, EncodeContext.Empty)
        buf.resetForRead()
        val decoded = V5PropertyBagCodec.decode(buf, DecodeContext.Empty)
        assertEquals(original.subscriptionIdentifiers, decoded.subscriptionIdentifiers)
    }

    @Test
    fun decodeRejectsDuplicateSessionExpiryInterval() {
        // Wire: VBI(10) <0x11 0x00 0x00 0x00 0x05> <0x11 0x00 0x00 0x00 0x06>.
        // Two SessionExpiryInterval (0x11) entries — Protocol Error per
        // spec §3.1.2.11.2 [MQTT-3.1.2-15].
        val wire =
            byteArrayOf(
                0x0A,
                0x11,
                0x00,
                0x00,
                0x00,
                0x05,
                0x11,
                0x00,
                0x00,
                0x00,
                0x06,
            )
        val buf = BufferFactory.Default.allocate(wire.size, ByteOrder.BIG_ENDIAN)
        buf.writeBytes(wire)
        buf.resetForRead()
        val ex =
            assertFailsWith<DecodeException> {
                V5PropertyBagCodec.decode(buf, DecodeContext.Empty)
            }
        assertTrue(
            ex.message!!.contains("SessionExpiryInterval"),
            "expected duplicate-SessionExpiryInterval diagnostic, got: ${ex.message}",
        )
        assertTrue(
            ex.message!!.contains("duplicate property identifier 0x11"),
            "expected duplicate-id diagnostic, got: ${ex.message}",
        )
    }

    @Test
    fun decodeRejectsDuplicateContentType() {
        // Wire: VBI(28) ContentType="a" + ContentType="b".
        val wire =
            byteArrayOf(
                0x08, // VBI body length = 8
                0x03,
                0x00,
                0x01,
                'a'.code.toByte(),
                0x03,
                0x00,
                0x01,
                'b'.code.toByte(),
            )
        val buf = BufferFactory.Default.allocate(wire.size, ByteOrder.BIG_ENDIAN)
        buf.writeBytes(wire)
        buf.resetForRead()
        val ex =
            assertFailsWith<DecodeException> {
                V5PropertyBagCodec.decode(buf, DecodeContext.Empty)
            }
        assertTrue(
            ex.message!!.contains("ContentType"),
            "expected duplicate-ContentType diagnostic, got: ${ex.message}",
        )
    }

    @Test
    fun decodeRejectsDuplicateReceiveMaximum() {
        val wire =
            byteArrayOf(
                0x06, // VBI body length = 6
                0x21,
                0x00,
                0x10, // ReceiveMaximum = 16
                0x21,
                0x00,
                0x20, // ReceiveMaximum = 32
            )
        val buf = BufferFactory.Default.allocate(wire.size, ByteOrder.BIG_ENDIAN)
        buf.writeBytes(wire)
        buf.resetForRead()
        val ex =
            assertFailsWith<DecodeException> {
                V5PropertyBagCodec.decode(buf, DecodeContext.Empty)
            }
        assertTrue(ex.message!!.contains("ReceiveMaximum"))
    }

    @Test
    fun toV5PropertyBagFromListEnforcesUniqueness() {
        // Mirror of the decoder rule: caller-side conversion from a flat list
        // to V5PropertyBag rejects duplicate unique-cardinality variants at
        // construction time. UserProperty / SubscriptionIdentifier still
        // append.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                listOf(
                    MqttV5Property.MessageExpiryInterval(seconds = 60u),
                    MqttV5Property.MessageExpiryInterval(seconds = 120u),
                ).toV5PropertyBag()
            }
        assertTrue(
            ex.message!!.contains("MessageExpiryInterval"),
            "expected duplicate-MessageExpiryInterval diagnostic, got: ${ex.message}",
        )
    }

    @Test
    fun toV5PropertyBagAccumulatesUserProperties() {
        val bag =
            listOf(
                MqttV5Property.UserProperty(key = "x", value = "1"),
                MqttV5Property.UserProperty(key = "y", value = "2"),
                MqttV5Property.UserProperty(key = "x", value = "3"),
            ).toV5PropertyBag()
        assertEquals(3, bag.userProperties.size)
        assertNull(bag.contentType)
        assertNull(bag.sessionExpiryInterval)
    }

    @Test
    fun ofConvenienceFactoryRoutesByVariant() {
        val bag =
            V5PropertyBag.of(
                MqttV5Property.MessageExpiryInterval(seconds = 60u),
                MqttV5Property.ContentType(value = "text/plain"),
                MqttV5Property.UserProperty(key = "k", value = "v"),
            )
        val messageExpiry = assertNotNull(bag.messageExpiryInterval)
        assertEquals(60u, messageExpiry.seconds)
        val contentType = assertNotNull(bag.contentType)
        assertEquals("text/plain", contentType.value)
        assertEquals(1, bag.userProperties.size)
        assertEquals("k", bag.userProperties[0].key)
    }

    @Test
    fun emptyListConvertsToEmptyBagSingleton() {
        assertSame(V5PropertyBag.EMPTY, emptyList<MqttV5Property>().toV5PropertyBag())
    }

    @Test
    fun isEmptyReturnsTrueForDefaultBag() {
        assertTrue(V5PropertyBag.EMPTY.isEmpty())
        assertTrue(V5PropertyBag().isEmpty())
    }

    @Test
    fun isEmptyReturnsFalseWhenAnyFieldSet() {
        assertTrue(!V5PropertyBag(reasonString = MqttV5Property.ReasonString(value = "x")).isEmpty())
        assertTrue(
            !V5PropertyBag(
                userProperties = listOf(MqttV5Property.UserProperty(key = "k", value = "v")),
            ).isEmpty(),
        )
    }
}
