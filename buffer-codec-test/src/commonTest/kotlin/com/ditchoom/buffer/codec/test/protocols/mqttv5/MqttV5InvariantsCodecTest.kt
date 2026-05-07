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
        // header invariant only fixes the high nibble. 0x32 (QoS=1) is
        // exercised throughout the existing suite — this assertion is
        // a sanity check that audit-2d didn't tighten it accidentally.
        MqttV5Packet.Publish<com.ditchoom.buffer.codec.test.protocols.payload.JpegImage>(
            header = MqttFixedHeader(0x3Fu), // all flag bits set
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
