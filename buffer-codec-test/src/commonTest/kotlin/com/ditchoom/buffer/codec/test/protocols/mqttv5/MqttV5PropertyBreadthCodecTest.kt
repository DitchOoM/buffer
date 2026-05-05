package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Phase J.M.5 slice 10 (Tier A) — round-trip + edge-cases for each
 * MQTT v5.0 property variant landed in this slice.
 *
 * Variants from slice 2 (MessageExpiryInterval, ContentType) are
 * exercised by [MqttV5PublishCodecTest] already; this suite covers the
 * remaining 22 non-VBI / non-binary variants. The `assertRoundTrip`
 * helper drives each property through [MqttV5PropertyCodec] (the
 * generated sealed-parent dispatcher) and asserts decode-equals-encode.
 *
 * Boolean-shaped variants additionally assert that
 * `init { require(value in 0u..1u) }` rejects values outside the spec
 * range — caller-protection for the v5-handwritten-audit Gap B finding.
 */
class MqttV5PropertyBreadthCodecTest {
    @Test
    fun payloadFormatIndicatorRoundTrips() {
        assertRoundTrip(MqttV5Property.PayloadFormatIndicator(value = 0u))
        assertRoundTrip(MqttV5Property.PayloadFormatIndicator(value = 1u))
    }

    @Test
    fun payloadFormatIndicatorRejectsOutOfRange() {
        assertFailsWith<IllegalArgumentException> {
            MqttV5Property.PayloadFormatIndicator(value = 2u)
        }
    }

    @Test
    fun responseTopicRoundTrips() {
        assertRoundTrip(MqttV5Property.ResponseTopic(value = "responses/abc"))
        assertRoundTrip(MqttV5Property.ResponseTopic(value = ""))
    }

    @Test
    fun sessionExpiryIntervalRoundTrips() {
        assertRoundTrip(MqttV5Property.SessionExpiryInterval(seconds = 0u))
        assertRoundTrip(MqttV5Property.SessionExpiryInterval(seconds = 0xFFFF_FFFFu))
    }

    @Test
    fun assignedClientIdentifierRoundTrips() {
        assertRoundTrip(MqttV5Property.AssignedClientIdentifier(value = "client-7c2f"))
    }

    @Test
    fun serverKeepAliveRoundTrips() {
        assertRoundTrip(MqttV5Property.ServerKeepAlive(seconds = 60u))
        assertRoundTrip(MqttV5Property.ServerKeepAlive(seconds = 0u))
    }

    @Test
    fun authenticationMethodRoundTrips() {
        assertRoundTrip(MqttV5Property.AuthenticationMethod(value = "SCRAM-SHA-256"))
    }

    @Test
    fun requestProblemInformationRoundTrips() {
        assertRoundTrip(MqttV5Property.RequestProblemInformation(value = 0u))
        assertRoundTrip(MqttV5Property.RequestProblemInformation(value = 1u))
    }

    @Test
    fun requestProblemInformationRejectsOutOfRange() {
        assertFailsWith<IllegalArgumentException> {
            MqttV5Property.RequestProblemInformation(value = 2u)
        }
    }

    @Test
    fun willDelayIntervalRoundTrips() {
        assertRoundTrip(MqttV5Property.WillDelayInterval(seconds = 30u))
    }

    @Test
    fun requestResponseInformationRoundTrips() {
        assertRoundTrip(MqttV5Property.RequestResponseInformation(value = 0u))
        assertRoundTrip(MqttV5Property.RequestResponseInformation(value = 1u))
    }

    @Test
    fun requestResponseInformationRejectsOutOfRange() {
        assertFailsWith<IllegalArgumentException> {
            MqttV5Property.RequestResponseInformation(value = 5u)
        }
    }

    @Test
    fun responseInformationRoundTrips() {
        assertRoundTrip(MqttV5Property.ResponseInformation(value = "broker.example/v1"))
    }

    @Test
    fun serverReferenceRoundTrips() {
        assertRoundTrip(MqttV5Property.ServerReference(value = "tcp://other-broker:1883"))
    }

    @Test
    fun reasonStringRoundTrips() {
        assertRoundTrip(MqttV5Property.ReasonString(value = "rate limit exceeded"))
    }

    @Test
    fun receiveMaximumRoundTrips() {
        assertRoundTrip(MqttV5Property.ReceiveMaximum(value = 1u))
        assertRoundTrip(MqttV5Property.ReceiveMaximum(value = 0xFFFFu))
    }

    @Test
    fun topicAliasMaximumRoundTrips() {
        assertRoundTrip(MqttV5Property.TopicAliasMaximum(value = 0u))
        assertRoundTrip(MqttV5Property.TopicAliasMaximum(value = 100u))
    }

    @Test
    fun topicAliasRoundTrips() {
        assertRoundTrip(MqttV5Property.TopicAlias(value = 1u))
    }

    @Test
    fun maximumQoSRoundTrips() {
        assertRoundTrip(MqttV5Property.MaximumQoS(value = 0u))
        assertRoundTrip(MqttV5Property.MaximumQoS(value = 1u))
    }

    @Test
    fun maximumQoSRejectsOutOfRange() {
        assertFailsWith<IllegalArgumentException> {
            MqttV5Property.MaximumQoS(value = 3u)
        }
    }

    @Test
    fun retainAvailableRoundTrips() {
        assertRoundTrip(MqttV5Property.RetainAvailable(value = 0u))
        assertRoundTrip(MqttV5Property.RetainAvailable(value = 1u))
    }

    @Test
    fun retainAvailableRejectsOutOfRange() {
        assertFailsWith<IllegalArgumentException> {
            MqttV5Property.RetainAvailable(value = 2u)
        }
    }

    @Test
    fun userPropertyRoundTrips() {
        // User Property is the only two-LP-string variant.
        assertRoundTrip(MqttV5Property.UserProperty(key = "x-trace-id", value = "abc-123"))
        assertRoundTrip(MqttV5Property.UserProperty(key = "", value = "empty-key-allowed"))
    }

    @Test
    fun maximumPacketSizeRoundTrips() {
        assertRoundTrip(MqttV5Property.MaximumPacketSize(value = 0x10_0000u))
    }

    @Test
    fun wildcardSubscriptionAvailableRoundTrips() {
        assertRoundTrip(MqttV5Property.WildcardSubscriptionAvailable(value = 0u))
        assertRoundTrip(MqttV5Property.WildcardSubscriptionAvailable(value = 1u))
    }

    @Test
    fun wildcardSubscriptionAvailableRejectsOutOfRange() {
        assertFailsWith<IllegalArgumentException> {
            MqttV5Property.WildcardSubscriptionAvailable(value = 7u)
        }
    }

    @Test
    fun subscriptionIdentifiersAvailableRoundTrips() {
        assertRoundTrip(MqttV5Property.SubscriptionIdentifiersAvailable(value = 0u))
        assertRoundTrip(MqttV5Property.SubscriptionIdentifiersAvailable(value = 1u))
    }

    @Test
    fun subscriptionIdentifiersAvailableRejectsOutOfRange() {
        assertFailsWith<IllegalArgumentException> {
            MqttV5Property.SubscriptionIdentifiersAvailable(value = 2u)
        }
    }

    @Test
    fun sharedSubscriptionAvailableRoundTrips() {
        assertRoundTrip(MqttV5Property.SharedSubscriptionAvailable(value = 0u))
        assertRoundTrip(MqttV5Property.SharedSubscriptionAvailable(value = 1u))
    }

    @Test
    fun sharedSubscriptionAvailableRejectsOutOfRange() {
        assertFailsWith<IllegalArgumentException> {
            MqttV5Property.SharedSubscriptionAvailable(value = 4u)
        }
    }

    private fun assertRoundTrip(original: MqttV5Property) {
        val buffer = BufferFactory.Default.allocate(128, ByteOrder.BIG_ENDIAN)
        MqttV5PropertyCodec.encode(buffer, original, EncodeContext.Empty)
        buffer.resetForRead()
        val decoded = MqttV5PropertyCodec.decode(buffer, DecodeContext.Empty)
        assertEquals(original, decoded)
        assertEquals(0, buffer.remaining(), "decode must consume all encoded bytes")
    }
}
