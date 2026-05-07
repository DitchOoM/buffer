package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttFixedHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Phase J.M.5 audit-2d — fixture-level invariants beyond the
 * cascading-trailer rule (audit-2c) and the reason-code allowlists
 * (covered in [MqttV5CascadingAcksCodecTest]).
 *
 * Two impossible-state classes are validated here:
 *
 *  - **Header byte** [MQTT-2.1.3-1]. Every non-PUBLISH variant has a
 *    canonical fixed-header byte; the low 4 bits are reserved-and-
 *    must-be-zero (or, for PUBREL / SUBSCRIBE / UNSUBSCRIBE, reserved-
 *    and-must-be-set to a specific pattern per §3.6.1 / §3.8.1 /
 *    §3.10.1). PUBLISH is the documented exception — its low 4 bits
 *    carry DUP/QoS/RETAIN flags, so only the high nibble is checked.
 *
 *  - **`Subscribe.topicFilters` non-empty** [MQTT-3.8.3-2]. The
 *    SUBSCRIBE payload "MUST contain at least one Topic Filter /
 *    Subscription Options pair".
 *
 *  Reason-code allowlist tests (PUBACK / PUBREC / PUBREL / PUBCOMP /
 *  UNSUBACK / DISCONNECT / AUTH) live in [MqttV5CascadingAcksCodecTest]
 *  alongside the cascade-invariant tests they extend.
 */
class MqttV5InvariantsCodecTest {
    @Test
    fun publishAcceptsVariableLowNibble() {
        // PUBLISH's low 4 bits encode DUP/QoS/RETAIN per §3.3.1; the
        // header invariant only fixes the high nibble (and the audit-2f
        // QoS=3 ban added on MqttFixedHeader). 0x3D = DUP + QoS=2 + RETAIN
        // exercises every legal flag bit, but not the reserved QoS=3
        // pattern banned by audit-2f.
        MqttV5Packet.Publish<com.ditchoom.buffer.codec.test.protocols.payload.JpegImage>(
            header = MqttFixedHeader(0x3Du),
            topic = "t",
            packetId =
                com.ditchoom.buffer.codec.test.protocols.payload
                    .PacketId(1u),
            properties = emptyList(),
            payload =
                com.ditchoom.buffer.codec.test.protocols.payload.JpegImage(
                    width = 0u,
                    height = 0u,
                    data = byteArrayOf(),
                ),
        )
    }

    @Test
    fun publishRejectsWrongHighNibble() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                MqttV5Packet.Publish<com.ditchoom.buffer.codec.test.protocols.payload.JpegImage>(
                    header = MqttFixedHeader(0x40u), // high nibble = 4 (PUBACK), not 3
                    topic = "t",
                    properties = emptyList(),
                    payload =
                        com.ditchoom.buffer.codec.test.protocols.payload.JpegImage(
                            width = 0u,
                            height = 0u,
                            data = byteArrayOf(),
                        ),
                )
            }
        assertTrue(
            ex.message!!.contains("PUBLISH header invariant"),
            "expected PUBLISH header diagnostic, got: ${ex.message}",
        )
    }

    @Test
    fun pubAckRejectsWrongHighNibble() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                MqttV5Packet.PubAck(header = MqttFixedHeader(0x10u), packetIdentifier = 0x0001u)
            }
        assertTrue(
            ex.message!!.contains("PUBACK header invariant"),
            "expected PUBACK header diagnostic, got: ${ex.message}",
        )
    }

    @Test
    fun pubAckRejectsWrongLowNibble() {
        // PUBACK low nibble is reserved-must-be-zero per §3.4.1; 0x41
        // is wire-invalid even though the high nibble is correct.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                MqttV5Packet.PubAck(header = MqttFixedHeader(0x41u), packetIdentifier = 0x0001u)
            }
        assertTrue(ex.message!!.contains("PUBACK header invariant"))
    }

    @Test
    fun pubRelRejectsCanonicalLowNibbleZero() {
        // PUBREL's canonical byte is 0x62 (low nibble 0010 reserved-and-
        // must-be-set per §3.6.1). 0x60 (low nibble 0000) is the most
        // common mistake and must reject.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                MqttV5Packet.PubRel(header = MqttFixedHeader(0x60u), packetIdentifier = 0x0001u)
            }
        assertTrue(ex.message!!.contains("PUBREL header invariant"))
    }

    @Test
    fun subscribeRejectsCanonicalLowNibbleZero() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                MqttV5Packet.Subscribe(
                    header = MqttFixedHeader(0x80u),
                    packetIdentifier = 0x0001u,
                    properties = emptyList(),
                    topicFilters = listOf(V5Subscription("t/1", V5SubscriptionOptions.of(qos = 0))),
                )
            }
        assertTrue(ex.message!!.contains("SUBSCRIBE header invariant"))
    }

    @Test
    fun unsubscribeRejectsCanonicalLowNibbleZero() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                MqttV5Packet.Unsubscribe(
                    header = MqttFixedHeader(0xA0u),
                    packetIdentifier = 0x0001u,
                    properties = emptyList(),
                    topics =
                        listOf(
                            com.ditchoom.buffer.codec.test.protocols.mqtt
                                .MqttUnsubscribeTopic("t"),
                        ),
                )
            }
        assertTrue(ex.message!!.contains("UNSUBSCRIBE header invariant"))
    }

    @Test
    fun pingReqRejectsWrongHeader() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                MqttV5Packet.PingReq(header = MqttFixedHeader(0xC1u))
            }
        assertTrue(ex.message!!.contains("PINGREQ header invariant"))
    }

    @Test
    fun subscribeRejectsEmptyTopicFilters() {
        // §3.8.3 — "The Payload of a SUBSCRIBE packet MUST contain at
        // least one Topic Filter / Subscription Options pair"
        // [MQTT-3.8.3-2]. Empty list is wire-invalid even though the
        // generated codec would happily round-trip an empty body.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                MqttV5Packet.Subscribe(
                    packetIdentifier = 0x0001u,
                    properties = emptyList(),
                    topicFilters = emptyList(),
                )
            }
        assertTrue(
            ex.message!!.contains("topicFilters must contain at least one filter"),
            "expected topicFilters diagnostic, got: ${ex.message}",
        )
    }

    // Phase J.M.5 slice 12 — V5SubscriptionOptions impossible-state guards
    // per §3.8.3.1.

    @Test
    fun subscriptionOptionsRejectsReservedBitsNonZero() {
        // [MQTT-3.8.3-1] — bits 6-7 are reserved-must-be-zero. Any of
        // 0x40, 0x80, 0xC0 trips the guard.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                V5SubscriptionOptions(0x40u)
            }
        assertTrue(
            ex.message!!.contains("reserved bits 6-7 must be zero"),
            "expected reserved-bits diagnostic, got: ${ex.message}",
        )
    }

    @Test
    fun subscriptionOptionsRejectsQosThree() {
        // [MQTT-3.8.3-2] — QoS=3 is reserved-must-not-be-used.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                V5SubscriptionOptions(0x03u)
            }
        assertTrue(
            ex.message!!.contains("QoS=3"),
            "expected QoS-3 diagnostic, got: ${ex.message}",
        )
    }

    @Test
    fun subscriptionOptionsRejectsRetainHandlingThree() {
        // [MQTT-3.8.3-4] — RetainHandling=3 is reserved.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                V5SubscriptionOptions(0x30u) // bits 4-5 = 11
            }
        assertTrue(
            ex.message!!.contains("RetainHandling=3"),
            "expected RetainHandling-3 diagnostic, got: ${ex.message}",
        )
    }

    // Phase J.M.5 audit-2e — MqttV5PropertyId invariant per §2.2.2.2.
    // Spot-checks a representative variant from each shape category
    // (scalar / boolean / string / VBI / two-string). The full breadth
    // (~25 variants) is structurally identical via
    // `requireMatchingPropertyId`; testing one per shape keeps the suite
    // tight while still proving every shape calls the helper.

    @Test
    fun messageExpiryIntervalRejectsMismatchedId() {
        // Scalar-body variant.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                MqttV5Property.MessageExpiryInterval(
                    id = MqttV5PropertyId(0xFFu),
                    seconds = 60u,
                )
            }
        assertTrue(
            ex.message!!.contains("MessageExpiryInterval id-byte invariant"),
            "expected id-byte diagnostic, got: ${ex.message}",
        )
    }

    @Test
    fun contentTypeRejectsMismatchedId() {
        // String-body variant.
        assertFailsWith<IllegalArgumentException> {
            MqttV5Property.ContentType(
                id = MqttV5PropertyId(0x00u),
                value = "text/plain",
            )
        }
    }

    @Test
    fun payloadFormatIndicatorRejectsMismatchedId() {
        // Boolean-shaped variant (already had a value-range init).
        assertFailsWith<IllegalArgumentException> {
            MqttV5Property.PayloadFormatIndicator(
                id = MqttV5PropertyId(0x42u),
                value = 1u,
            )
        }
    }

    @Test
    fun subscriptionIdentifierRejectsMismatchedId() {
        // VBI-body variant (already had a value-range init).
        assertFailsWith<IllegalArgumentException> {
            MqttV5Property.SubscriptionIdentifier(
                id = MqttV5PropertyId(0x10u),
                value = 1u,
            )
        }
    }

    @Test
    fun userPropertyRejectsMismatchedId() {
        // Two-string variant.
        assertFailsWith<IllegalArgumentException> {
            MqttV5Property.UserProperty(
                id = MqttV5PropertyId(0x00u),
                key = "k",
                value = "v",
            )
        }
    }

    // Phase J.M.5 audit-2f — pre-slice-15 impossible-state sweep.
    // FIX-TODAY findings + NEEDS-EMITTER-WORK init-block fallbacks.

    @Test
    fun mqttConnectFlagsRejectsReservedBitOne() {
        // §3.1.2.3 [MQTT-3.1.2-3] — bit 0 is reserved-and-must-be-zero.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                com.ditchoom.buffer.codec.test.protocols.mqtt
                    .MqttConnectFlags(0x03u)
            }
        assertTrue(
            ex.message!!.contains("reserved bit 0 must be zero"),
            "expected reserved-bit diagnostic, got: ${ex.message}",
        )
    }

    @Test
    fun mqttConnectFlagsRejectsWillQosThree() {
        // §3.1.2.6 [MQTT-3.1.2-13] — willQoS=3 is malformed.
        // 0x1C = 0001 1100: willPresent + willQoS bits 3-4 = 11.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                com.ditchoom.buffer.codec.test.protocols.mqtt
                    .MqttConnectFlags(0x1Cu)
            }
        assertTrue(
            ex.message!!.contains("willQoS must not be 3"),
            "expected willQoS=3 diagnostic, got: ${ex.message}",
        )
    }

    @Test
    fun mqttConnectFlagsRejectsWillQosNonZeroWithoutWill() {
        // §3.1.2.6 [MQTT-3.1.2-14] — if willPresent is 0, willQoS MUST be 0.
        // 0x08 = 0000 1000: bit 3 set (willQoS bit 0 = 1, so willQoS = 1),
        // willPresent = 0.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                com.ditchoom.buffer.codec.test.protocols.mqtt
                    .MqttConnectFlags(0x08u)
            }
        assertTrue(
            ex.message!!.contains("willQoS must be 0 when willPresent is false"),
            "expected willQoS-without-will diagnostic, got: ${ex.message}",
        )
    }

    @Test
    fun packetIdRejectsZero() {
        // §2.2.1 [MQTT-2.2.1-3] — packet identifier must be > 0.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                com.ditchoom.buffer.codec.test.protocols.payload
                    .PacketId(0u)
            }
        assertTrue(
            ex.message!!.contains("PacketId must be > 0"),
            "expected PacketId-zero diagnostic, got: ${ex.message}",
        )
    }

    @Test
    fun mqttFixedHeaderRejectsPublishQosThree() {
        // §3.3.1.2 [MQTT-3.3.1-4] — PUBLISH with QoS bits = 11 is malformed.
        // 0x36 = type 3 (PUBLISH), bits 1-2 = 11 (QoS=3).
        val ex =
            assertFailsWith<IllegalArgumentException> {
                MqttFixedHeader(0x36u)
            }
        assertTrue(
            ex.message!!.contains("PUBLISH QoS=3"),
            "expected QoS=3 diagnostic, got: ${ex.message}",
        )
    }

    @Test
    fun v3ConnectRejectsPasswordWithoutUsername() {
        // §3.1.2.9 [MQTT-3.1.2-22] — passwordPresent requires usernamePresent
        // in v3.1.1 (v5 dropped this rule).
        // 0x42 = 0100 0010: cleanSession + passwordPresent, no usernamePresent.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                com.ditchoom.buffer.codec.test.protocols.mqtt.MqttPacket.Connect(
                    protocolName = "MQTT",
                    protocolLevel = 4u,
                    connectFlags =
                        com.ditchoom.buffer.codec.test.protocols.mqtt
                            .MqttConnectFlags(0x42u),
                    keepAliveSeconds = 0u,
                    clientId = "id",
                    password =
                        com.ditchoom.buffer.codec.test.protocols.payload
                            .BinaryData("pw".encodeToByteArray()),
                )
            }
        assertTrue(
            ex.message!!.contains("passwordPresent requires usernamePresent"),
            "expected password-without-username diagnostic, got: ${ex.message}",
        )
    }

    @Test
    fun v3PublishRejectsPacketIdSetWithQosZero() {
        // §2.2.1 — packet identifier present iff QoS > 0.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                com.ditchoom.buffer.codec.test.protocols.mqtt.MqttPacket.Publish<
                    com.ditchoom.buffer.codec.test.protocols.payload.JpegImage,
                >(
                    header = MqttFixedHeader(0x30u), // QoS=0
                    topic = "t",
                    packetId =
                        com.ditchoom.buffer.codec.test.protocols.payload
                            .PacketId(0x0001u),
                    payload =
                        com.ditchoom.buffer.codec.test.protocols.payload.JpegImage(
                            width = 0u,
                            height = 0u,
                            data = byteArrayOf(),
                        ),
                )
            }
        assertTrue(ex.message!!.contains("v3 PUBLISH invariant"))
    }

    @Test
    fun v5ConnAckRejectsReservedFlagBits() {
        // §3.2.2.1 [MQTT-3.2.2-1] — connectAckFlags bits 7-1 reserved=0.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                MqttV5Packet.ConnAck(
                    connectAckFlags = 0x02u, // bit 1 set
                    reasonCode =
                        com.ditchoom.buffer.codec.test.protocols.mqttv5.connack.V5ConnectReasonCode
                            .Success(),
                    properties = emptyList(),
                )
            }
        assertTrue(
            ex.message!!.contains("reserved bits 7-1"),
            "expected ConnAck reserved-bits diagnostic, got: ${ex.message}",
        )
    }

    @Test
    fun v5PublishRejectsPacketIdMissingWhenQosOne() {
        // §2.2.1 — packet identifier present iff QoS > 0. Mirror of the v3
        // PUBLISH cross-bit invariant.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                MqttV5Packet.Publish<com.ditchoom.buffer.codec.test.protocols.payload.JpegImage>(
                    header = MqttFixedHeader(0x32u), // QoS=1
                    topic = "t",
                    packetId = null,
                    properties = emptyList(),
                    payload =
                        com.ditchoom.buffer.codec.test.protocols.payload.JpegImage(
                            width = 0u,
                            height = 0u,
                            data = byteArrayOf(),
                        ),
                )
            }
        assertTrue(ex.message!!.contains("v5 PUBLISH invariant"))
    }

    @Test
    fun v5SubAckRejectsEmptyReasonCodes() {
        // §3.9.3 — at least one reason code per topic filter.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                MqttV5Packet.SubAck(
                    packetIdentifier = 0x0001u,
                    properties = emptyList(),
                    reasonCodes = emptyList(),
                )
            }
        assertTrue(ex.message!!.contains("reasonCodes must contain at least one"))
    }

    @Test
    fun v5UnsubscribeRejectsEmptyTopics() {
        // §3.10.3 [MQTT-3.10.3-2] — UNSUBSCRIBE payload must contain at
        // least one Topic Filter.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                MqttV5Packet.Unsubscribe(
                    packetIdentifier = 0x0001u,
                    properties = emptyList(),
                    topics = emptyList(),
                )
            }
        assertTrue(ex.message!!.contains("topics must contain at least one filter"))
    }

    @Test
    fun receiveMaximumRejectsZero() {
        // §3.1.2.11.3 [MQTT-3.1.2-32].
        val ex = assertFailsWith<IllegalArgumentException> { MqttV5Property.ReceiveMaximum(value = 0u) }
        assertTrue(ex.message!!.contains("ReceiveMaximum value must be > 0"))
    }

    @Test
    fun maximumPacketSizeRejectsZero() {
        // §3.1.2.11.4 [MQTT-3.1.2-31].
        val ex = assertFailsWith<IllegalArgumentException> { MqttV5Property.MaximumPacketSize(value = 0u) }
        assertTrue(ex.message!!.contains("MaximumPacketSize value must be > 0"))
    }

    @Test
    fun topicAliasRejectsZero() {
        // §3.3.2.3.4 [MQTT-3.3.2-8].
        val ex = assertFailsWith<IllegalArgumentException> { MqttV5Property.TopicAlias(value = 0u) }
        assertTrue(ex.message!!.contains("TopicAlias value must be > 0"))
    }

    @Test
    fun subscriptionOptionsExposesAllFourFields() {
        // QoS=1, NoLocal=true, RetainAsPublished=false, RetainHandling=2 →
        // 0010_0101 = 0x25.
        val opts =
            V5SubscriptionOptions.of(
                qos = 1,
                noLocal = true,
                retainAsPublished = false,
                retainHandling = 2,
            )
        assertEquals(0x25u.toUByte(), opts.raw)
        assertEquals(1, opts.qos)
        assertEquals(true, opts.noLocal)
        assertEquals(false, opts.retainAsPublished)
        assertEquals(2, opts.retainHandling)
    }
}
