package com.ditchoom.buffer.codec.test.protocols.mqttv5

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.codec.test.protocols.mqtt.MqttRemainingLengthCodec
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.utf8Length

/**
 * Phase J.M.5 slice 15f — typed v5 property bag.
 *
 * Wraps the property-bag region every v5 control packet carries between
 * its variable header and payload (CONNECT / CONNACK / PUBLISH / PUBACK
 * / PUBREC / PUBREL / PUBCOMP / SUBSCRIBE / SUBACK / UNSUBSCRIBE /
 * UNSUBACK / DISCONNECT / AUTH). MQTT v5 §2.2.2 names the wire shape;
 * §3.1.2.11 et al. require each property identifier to appear at most
 * once per bag — duplicates are a Protocol Error. Two variants are
 * exempt: [MqttV5Property.UserProperty] (§3.1.2.11.8 — independent
 * key/value pairs may repeat) and [MqttV5Property.SubscriptionIdentifier]
 * (§3.3.2.3.8 — multiple ids may forward on PUBLISH when a message
 * matched several subscriptions).
 *
 * Modeling each unique-cardinality property as a typed nullable field
 * (and the two repeatable variants as `List`s) makes duplicates
 * unrepresentable at construction time — the type system enforces the
 * invariant on the encode side. [V5PropertyBagCodec] enforces the same
 * invariant on the decode side: a duplicate unique-cardinality property
 * raises [DecodeException].
 *
 * Field-order in the constructor follows ascending property identifier
 * (§2.2.2.2). Wire-order on encode matches via [toList].
 *
 * Closes the cross-property-uniqueness gap that [`v5_audit_gaps.md`]
 * tracked as the deferred slice 15e — renamed slice 15f after slice 15e
 * was reused for `@RemainingBytes String` (commit `bb7cca77`).
 */
data class V5PropertyBag(
    val payloadFormatIndicator: MqttV5Property.PayloadFormatIndicator? = null,
    val messageExpiryInterval: MqttV5Property.MessageExpiryInterval? = null,
    val contentType: MqttV5Property.ContentType? = null,
    val responseTopic: MqttV5Property.ResponseTopic? = null,
    val correlationData: MqttV5Property.CorrelationData? = null,
    val subscriptionIdentifiers: List<MqttV5Property.SubscriptionIdentifier> = emptyList(),
    val sessionExpiryInterval: MqttV5Property.SessionExpiryInterval? = null,
    val assignedClientIdentifier: MqttV5Property.AssignedClientIdentifier? = null,
    val serverKeepAlive: MqttV5Property.ServerKeepAlive? = null,
    val authenticationMethod: MqttV5Property.AuthenticationMethod? = null,
    val authenticationData: MqttV5Property.AuthenticationData? = null,
    val requestProblemInformation: MqttV5Property.RequestProblemInformation? = null,
    val willDelayInterval: MqttV5Property.WillDelayInterval? = null,
    val requestResponseInformation: MqttV5Property.RequestResponseInformation? = null,
    val responseInformation: MqttV5Property.ResponseInformation? = null,
    val serverReference: MqttV5Property.ServerReference? = null,
    val reasonString: MqttV5Property.ReasonString? = null,
    val receiveMaximum: MqttV5Property.ReceiveMaximum? = null,
    val topicAliasMaximum: MqttV5Property.TopicAliasMaximum? = null,
    val topicAlias: MqttV5Property.TopicAlias? = null,
    val maximumQoS: MqttV5Property.MaximumQoS? = null,
    val retainAvailable: MqttV5Property.RetainAvailable? = null,
    val userProperties: List<MqttV5Property.UserProperty> = emptyList(),
    val maximumPacketSize: MqttV5Property.MaximumPacketSize? = null,
    val wildcardSubscriptionAvailable: MqttV5Property.WildcardSubscriptionAvailable? = null,
    val subscriptionIdentifiersAvailable: MqttV5Property.SubscriptionIdentifiersAvailable? = null,
    val sharedSubscriptionAvailable: MqttV5Property.SharedSubscriptionAvailable? = null,
) {
    /**
     * Returns set properties in ascending property-id order (lists in
     * insertion order at their id position). The MQTT v5 spec doesn't
     * constrain ordering across distinct property ids, but UserProperty
     * order within its list is significant (§3.1.2.11.8) and is
     * preserved by emitting the list at the 0x26 position; same for
     * SubscriptionIdentifier at 0x0B.
     */
    fun toList(): List<MqttV5Property> =
        buildList(approximateCount()) {
            payloadFormatIndicator?.let(::add)
            messageExpiryInterval?.let(::add)
            contentType?.let(::add)
            responseTopic?.let(::add)
            correlationData?.let(::add)
            addAll(subscriptionIdentifiers)
            sessionExpiryInterval?.let(::add)
            assignedClientIdentifier?.let(::add)
            serverKeepAlive?.let(::add)
            authenticationMethod?.let(::add)
            authenticationData?.let(::add)
            requestProblemInformation?.let(::add)
            willDelayInterval?.let(::add)
            requestResponseInformation?.let(::add)
            responseInformation?.let(::add)
            serverReference?.let(::add)
            reasonString?.let(::add)
            receiveMaximum?.let(::add)
            topicAliasMaximum?.let(::add)
            topicAlias?.let(::add)
            maximumQoS?.let(::add)
            retainAvailable?.let(::add)
            addAll(userProperties)
            maximumPacketSize?.let(::add)
            wildcardSubscriptionAvailable?.let(::add)
            subscriptionIdentifiersAvailable?.let(::add)
            sharedSubscriptionAvailable?.let(::add)
        }

    private fun approximateCount(): Int {
        var n = subscriptionIdentifiers.size + userProperties.size
        if (payloadFormatIndicator != null) n++
        if (messageExpiryInterval != null) n++
        if (contentType != null) n++
        if (responseTopic != null) n++
        if (correlationData != null) n++
        if (sessionExpiryInterval != null) n++
        if (assignedClientIdentifier != null) n++
        if (serverKeepAlive != null) n++
        if (authenticationMethod != null) n++
        if (authenticationData != null) n++
        if (requestProblemInformation != null) n++
        if (willDelayInterval != null) n++
        if (requestResponseInformation != null) n++
        if (responseInformation != null) n++
        if (serverReference != null) n++
        if (reasonString != null) n++
        if (receiveMaximum != null) n++
        if (topicAliasMaximum != null) n++
        if (topicAlias != null) n++
        if (maximumQoS != null) n++
        if (retainAvailable != null) n++
        if (maximumPacketSize != null) n++
        if (wildcardSubscriptionAvailable != null) n++
        if (subscriptionIdentifiersAvailable != null) n++
        if (sharedSubscriptionAvailable != null) n++
        return n
    }

    fun isEmpty(): Boolean =
        payloadFormatIndicator == null &&
            messageExpiryInterval == null &&
            contentType == null &&
            responseTopic == null &&
            correlationData == null &&
            subscriptionIdentifiers.isEmpty() &&
            sessionExpiryInterval == null &&
            assignedClientIdentifier == null &&
            serverKeepAlive == null &&
            authenticationMethod == null &&
            authenticationData == null &&
            requestProblemInformation == null &&
            willDelayInterval == null &&
            requestResponseInformation == null &&
            responseInformation == null &&
            serverReference == null &&
            reasonString == null &&
            receiveMaximum == null &&
            topicAliasMaximum == null &&
            topicAlias == null &&
            maximumQoS == null &&
            retainAvailable == null &&
            userProperties.isEmpty() &&
            maximumPacketSize == null &&
            wildcardSubscriptionAvailable == null &&
            subscriptionIdentifiersAvailable == null &&
            sharedSubscriptionAvailable == null

    companion object {
        val EMPTY: V5PropertyBag = V5PropertyBag()

        /**
         * Builds a [V5PropertyBag] from [properties] in arbitrary order,
         * routing each entry into its typed slot. Duplicate
         * unique-cardinality properties raise [IllegalArgumentException]
         * — the same invariant [V5PropertyBagCodec] enforces on decode.
         * UserProperty and SubscriptionIdentifier accumulate in argument
         * order. Used by tests and migration code that already construct
         * `List<MqttV5Property>`.
         */
        fun of(vararg properties: MqttV5Property): V5PropertyBag = properties.asList().toV5PropertyBag()
    }
}

/**
 * Routes a `List<MqttV5Property>` into a typed [V5PropertyBag]. Mirrors
 * the decoder's per-id dispatch and applies the same uniqueness invariant
 * — duplicate unique-cardinality properties raise
 * [IllegalArgumentException].
 */
fun List<MqttV5Property>.toV5PropertyBag(): V5PropertyBag {
    if (isEmpty()) return V5PropertyBag.EMPTY
    var payloadFormatIndicator: MqttV5Property.PayloadFormatIndicator? = null
    var messageExpiryInterval: MqttV5Property.MessageExpiryInterval? = null
    var contentType: MqttV5Property.ContentType? = null
    var responseTopic: MqttV5Property.ResponseTopic? = null
    var correlationData: MqttV5Property.CorrelationData? = null
    val subscriptionIdentifiers = mutableListOf<MqttV5Property.SubscriptionIdentifier>()
    var sessionExpiryInterval: MqttV5Property.SessionExpiryInterval? = null
    var assignedClientIdentifier: MqttV5Property.AssignedClientIdentifier? = null
    var serverKeepAlive: MqttV5Property.ServerKeepAlive? = null
    var authenticationMethod: MqttV5Property.AuthenticationMethod? = null
    var authenticationData: MqttV5Property.AuthenticationData? = null
    var requestProblemInformation: MqttV5Property.RequestProblemInformation? = null
    var willDelayInterval: MqttV5Property.WillDelayInterval? = null
    var requestResponseInformation: MqttV5Property.RequestResponseInformation? = null
    var responseInformation: MqttV5Property.ResponseInformation? = null
    var serverReference: MqttV5Property.ServerReference? = null
    var reasonString: MqttV5Property.ReasonString? = null
    var receiveMaximum: MqttV5Property.ReceiveMaximum? = null
    var topicAliasMaximum: MqttV5Property.TopicAliasMaximum? = null
    var topicAlias: MqttV5Property.TopicAlias? = null
    var maximumQoS: MqttV5Property.MaximumQoS? = null
    var retainAvailable: MqttV5Property.RetainAvailable? = null
    val userProperties = mutableListOf<MqttV5Property.UserProperty>()
    var maximumPacketSize: MqttV5Property.MaximumPacketSize? = null
    var wildcardSubscriptionAvailable: MqttV5Property.WildcardSubscriptionAvailable? = null
    var subscriptionIdentifiersAvailable: MqttV5Property.SubscriptionIdentifiersAvailable? = null
    var sharedSubscriptionAvailable: MqttV5Property.SharedSubscriptionAvailable? = null
    for (prop in this) {
        when (prop) {
            is MqttV5Property.PayloadFormatIndicator -> {
                require(payloadFormatIndicator == null) { dupMsg("PayloadFormatIndicator", 0x01) }
                payloadFormatIndicator = prop
            }
            is MqttV5Property.MessageExpiryInterval -> {
                require(messageExpiryInterval == null) { dupMsg("MessageExpiryInterval", 0x02) }
                messageExpiryInterval = prop
            }
            is MqttV5Property.ContentType -> {
                require(contentType == null) { dupMsg("ContentType", 0x03) }
                contentType = prop
            }
            is MqttV5Property.ResponseTopic -> {
                require(responseTopic == null) { dupMsg("ResponseTopic", 0x08) }
                responseTopic = prop
            }
            is MqttV5Property.CorrelationData -> {
                require(correlationData == null) { dupMsg("CorrelationData", 0x09) }
                correlationData = prop
            }
            is MqttV5Property.SubscriptionIdentifier -> subscriptionIdentifiers += prop
            is MqttV5Property.SessionExpiryInterval -> {
                require(sessionExpiryInterval == null) { dupMsg("SessionExpiryInterval", 0x11) }
                sessionExpiryInterval = prop
            }
            is MqttV5Property.AssignedClientIdentifier -> {
                require(assignedClientIdentifier == null) { dupMsg("AssignedClientIdentifier", 0x12) }
                assignedClientIdentifier = prop
            }
            is MqttV5Property.ServerKeepAlive -> {
                require(serverKeepAlive == null) { dupMsg("ServerKeepAlive", 0x13) }
                serverKeepAlive = prop
            }
            is MqttV5Property.AuthenticationMethod -> {
                require(authenticationMethod == null) { dupMsg("AuthenticationMethod", 0x15) }
                authenticationMethod = prop
            }
            is MqttV5Property.AuthenticationData -> {
                require(authenticationData == null) { dupMsg("AuthenticationData", 0x16) }
                authenticationData = prop
            }
            is MqttV5Property.RequestProblemInformation -> {
                require(requestProblemInformation == null) { dupMsg("RequestProblemInformation", 0x17) }
                requestProblemInformation = prop
            }
            is MqttV5Property.WillDelayInterval -> {
                require(willDelayInterval == null) { dupMsg("WillDelayInterval", 0x18) }
                willDelayInterval = prop
            }
            is MqttV5Property.RequestResponseInformation -> {
                require(requestResponseInformation == null) { dupMsg("RequestResponseInformation", 0x19) }
                requestResponseInformation = prop
            }
            is MqttV5Property.ResponseInformation -> {
                require(responseInformation == null) { dupMsg("ResponseInformation", 0x1A) }
                responseInformation = prop
            }
            is MqttV5Property.ServerReference -> {
                require(serverReference == null) { dupMsg("ServerReference", 0x1C) }
                serverReference = prop
            }
            is MqttV5Property.ReasonString -> {
                require(reasonString == null) { dupMsg("ReasonString", 0x1F) }
                reasonString = prop
            }
            is MqttV5Property.ReceiveMaximum -> {
                require(receiveMaximum == null) { dupMsg("ReceiveMaximum", 0x21) }
                receiveMaximum = prop
            }
            is MqttV5Property.TopicAliasMaximum -> {
                require(topicAliasMaximum == null) { dupMsg("TopicAliasMaximum", 0x22) }
                topicAliasMaximum = prop
            }
            is MqttV5Property.TopicAlias -> {
                require(topicAlias == null) { dupMsg("TopicAlias", 0x23) }
                topicAlias = prop
            }
            is MqttV5Property.MaximumQoS -> {
                require(maximumQoS == null) { dupMsg("MaximumQoS", 0x24) }
                maximumQoS = prop
            }
            is MqttV5Property.RetainAvailable -> {
                require(retainAvailable == null) { dupMsg("RetainAvailable", 0x25) }
                retainAvailable = prop
            }
            is MqttV5Property.UserProperty -> userProperties += prop
            is MqttV5Property.MaximumPacketSize -> {
                require(maximumPacketSize == null) { dupMsg("MaximumPacketSize", 0x27) }
                maximumPacketSize = prop
            }
            is MqttV5Property.WildcardSubscriptionAvailable -> {
                require(wildcardSubscriptionAvailable == null) { dupMsg("WildcardSubscriptionAvailable", 0x28) }
                wildcardSubscriptionAvailable = prop
            }
            is MqttV5Property.SubscriptionIdentifiersAvailable -> {
                require(subscriptionIdentifiersAvailable == null) { dupMsg("SubscriptionIdentifiersAvailable", 0x29) }
                subscriptionIdentifiersAvailable = prop
            }
            is MqttV5Property.SharedSubscriptionAvailable -> {
                require(sharedSubscriptionAvailable == null) { dupMsg("SharedSubscriptionAvailable", 0x2A) }
                sharedSubscriptionAvailable = prop
            }
        }
    }
    return V5PropertyBag(
        payloadFormatIndicator = payloadFormatIndicator,
        messageExpiryInterval = messageExpiryInterval,
        contentType = contentType,
        responseTopic = responseTopic,
        correlationData = correlationData,
        subscriptionIdentifiers = subscriptionIdentifiers,
        sessionExpiryInterval = sessionExpiryInterval,
        assignedClientIdentifier = assignedClientIdentifier,
        serverKeepAlive = serverKeepAlive,
        authenticationMethod = authenticationMethod,
        authenticationData = authenticationData,
        requestProblemInformation = requestProblemInformation,
        willDelayInterval = willDelayInterval,
        requestResponseInformation = requestResponseInformation,
        responseInformation = responseInformation,
        serverReference = serverReference,
        reasonString = reasonString,
        receiveMaximum = receiveMaximum,
        topicAliasMaximum = topicAliasMaximum,
        topicAlias = topicAlias,
        maximumQoS = maximumQoS,
        retainAvailable = retainAvailable,
        userProperties = userProperties,
        maximumPacketSize = maximumPacketSize,
        wildcardSubscriptionAvailable = wildcardSubscriptionAvailable,
        subscriptionIdentifiersAvailable = subscriptionIdentifiersAvailable,
        sharedSubscriptionAvailable = sharedSubscriptionAvailable,
    )
}

private fun dupMsg(
    name: String,
    id: Int,
): String =
    "v5 property bag: duplicate $name (id 0x${id.toString(16).padStart(2, '0')}) — only " +
        "UserProperty (0x26) and SubscriptionIdentifier (0x0B) may repeat (spec §2.2.2)"

/**
 * Self-framing `Codec<V5PropertyBag>` — wire shape per MQTT v5 §2.2.2:
 * `<VBI body length> <0..N property entries>`. Internally drives
 * [MqttRemainingLengthCodec] for the VBI prefix and the generated
 * `MqttV5PropertyCodec` dispatcher for each property entry.
 *
 * On the decode side, raises [DecodeException] for any
 * unique-cardinality property that appears more than once in a single
 * bag — closes the spec gap that motivated slice 15f. Repeats of
 * [MqttV5Property.UserProperty] and
 * [MqttV5Property.SubscriptionIdentifier] are accepted and accumulated
 * into the bag's lists in wire order.
 *
 * The codec self-frames its prefix, so v5 packets reference it as
 * `@UseCodec(V5PropertyBagCodec::class) val properties: V5PropertyBag`
 * (no `@LengthPrefixed`). For cascading-trailer packets (PUBACK / PUBREC
 * / PUBREL / PUBCOMP / UNSUBACK / DISCONNECT / AUTH) the field is
 * nullable and gated by `@When("remaining >= 1")` — slice 11a's
 * `@When @UseCodec val: T?` shape passes the same self-framing codec
 * call through.
 *
 * `peekFrameSize` returns `NoFraming` because the v5 dispatcher's outer
 * `MqttRemainingLengthCodec` already drives upstream peek (the bounding
 * UseCodecScalar walker on every `MqttV5Packet*Codec`). The bag's own
 * VBI prefix is irrelevant for packet-level framing.
 */
object V5PropertyBagCodec : Codec<V5PropertyBag> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): V5PropertyBag {
        val bodyLen = MqttRemainingLengthCodec.decode(buffer, context).toInt()
        if (bodyLen == 0) return V5PropertyBag.EMPTY

        val outerLimit = buffer.limit()
        val bodyEnd = buffer.position() + bodyLen
        if (bodyEnd > outerLimit) {
            throw DecodeException(
                fieldPath = "V5PropertyBag",
                bufferPosition = buffer.position(),
                expected = "VBI body length <= $outerLimit",
                actual = "body extends to $bodyEnd",
            )
        }
        buffer.setLimit(bodyEnd)

        var payloadFormatIndicator: MqttV5Property.PayloadFormatIndicator? = null
        var messageExpiryInterval: MqttV5Property.MessageExpiryInterval? = null
        var contentType: MqttV5Property.ContentType? = null
        var responseTopic: MqttV5Property.ResponseTopic? = null
        var correlationData: MqttV5Property.CorrelationData? = null
        val subscriptionIdentifiers = mutableListOf<MqttV5Property.SubscriptionIdentifier>()
        var sessionExpiryInterval: MqttV5Property.SessionExpiryInterval? = null
        var assignedClientIdentifier: MqttV5Property.AssignedClientIdentifier? = null
        var serverKeepAlive: MqttV5Property.ServerKeepAlive? = null
        var authenticationMethod: MqttV5Property.AuthenticationMethod? = null
        var authenticationData: MqttV5Property.AuthenticationData? = null
        var requestProblemInformation: MqttV5Property.RequestProblemInformation? = null
        var willDelayInterval: MqttV5Property.WillDelayInterval? = null
        var requestResponseInformation: MqttV5Property.RequestResponseInformation? = null
        var responseInformation: MqttV5Property.ResponseInformation? = null
        var serverReference: MqttV5Property.ServerReference? = null
        var reasonString: MqttV5Property.ReasonString? = null
        var receiveMaximum: MqttV5Property.ReceiveMaximum? = null
        var topicAliasMaximum: MqttV5Property.TopicAliasMaximum? = null
        var topicAlias: MqttV5Property.TopicAlias? = null
        var maximumQoS: MqttV5Property.MaximumQoS? = null
        var retainAvailable: MqttV5Property.RetainAvailable? = null
        val userProperties = mutableListOf<MqttV5Property.UserProperty>()
        var maximumPacketSize: MqttV5Property.MaximumPacketSize? = null
        var wildcardSubscriptionAvailable: MqttV5Property.WildcardSubscriptionAvailable? = null
        var subscriptionIdentifiersAvailable: MqttV5Property.SubscriptionIdentifiersAvailable? = null
        var sharedSubscriptionAvailable: MqttV5Property.SharedSubscriptionAvailable? = null

        try {
            while (buffer.position() < buffer.limit()) {
                val entryStart = buffer.position()
                when (val prop = MqttV5PropertyCodec.decode(buffer, context)) {
                    is MqttV5Property.PayloadFormatIndicator -> {
                        if (payloadFormatIndicator != null) duplicate(entryStart, "PayloadFormatIndicator", 0x01)
                        payloadFormatIndicator = prop
                    }
                    is MqttV5Property.MessageExpiryInterval -> {
                        if (messageExpiryInterval != null) duplicate(entryStart, "MessageExpiryInterval", 0x02)
                        messageExpiryInterval = prop
                    }
                    is MqttV5Property.ContentType -> {
                        if (contentType != null) duplicate(entryStart, "ContentType", 0x03)
                        contentType = prop
                    }
                    is MqttV5Property.ResponseTopic -> {
                        if (responseTopic != null) duplicate(entryStart, "ResponseTopic", 0x08)
                        responseTopic = prop
                    }
                    is MqttV5Property.CorrelationData -> {
                        if (correlationData != null) duplicate(entryStart, "CorrelationData", 0x09)
                        correlationData = prop
                    }
                    is MqttV5Property.SubscriptionIdentifier -> subscriptionIdentifiers += prop
                    is MqttV5Property.SessionExpiryInterval -> {
                        if (sessionExpiryInterval != null) duplicate(entryStart, "SessionExpiryInterval", 0x11)
                        sessionExpiryInterval = prop
                    }
                    is MqttV5Property.AssignedClientIdentifier -> {
                        if (assignedClientIdentifier != null) duplicate(entryStart, "AssignedClientIdentifier", 0x12)
                        assignedClientIdentifier = prop
                    }
                    is MqttV5Property.ServerKeepAlive -> {
                        if (serverKeepAlive != null) duplicate(entryStart, "ServerKeepAlive", 0x13)
                        serverKeepAlive = prop
                    }
                    is MqttV5Property.AuthenticationMethod -> {
                        if (authenticationMethod != null) duplicate(entryStart, "AuthenticationMethod", 0x15)
                        authenticationMethod = prop
                    }
                    is MqttV5Property.AuthenticationData -> {
                        if (authenticationData != null) duplicate(entryStart, "AuthenticationData", 0x16)
                        authenticationData = prop
                    }
                    is MqttV5Property.RequestProblemInformation -> {
                        if (requestProblemInformation != null) duplicate(entryStart, "RequestProblemInformation", 0x17)
                        requestProblemInformation = prop
                    }
                    is MqttV5Property.WillDelayInterval -> {
                        if (willDelayInterval != null) duplicate(entryStart, "WillDelayInterval", 0x18)
                        willDelayInterval = prop
                    }
                    is MqttV5Property.RequestResponseInformation -> {
                        if (requestResponseInformation != null) duplicate(entryStart, "RequestResponseInformation", 0x19)
                        requestResponseInformation = prop
                    }
                    is MqttV5Property.ResponseInformation -> {
                        if (responseInformation != null) duplicate(entryStart, "ResponseInformation", 0x1A)
                        responseInformation = prop
                    }
                    is MqttV5Property.ServerReference -> {
                        if (serverReference != null) duplicate(entryStart, "ServerReference", 0x1C)
                        serverReference = prop
                    }
                    is MqttV5Property.ReasonString -> {
                        if (reasonString != null) duplicate(entryStart, "ReasonString", 0x1F)
                        reasonString = prop
                    }
                    is MqttV5Property.ReceiveMaximum -> {
                        if (receiveMaximum != null) duplicate(entryStart, "ReceiveMaximum", 0x21)
                        receiveMaximum = prop
                    }
                    is MqttV5Property.TopicAliasMaximum -> {
                        if (topicAliasMaximum != null) duplicate(entryStart, "TopicAliasMaximum", 0x22)
                        topicAliasMaximum = prop
                    }
                    is MqttV5Property.TopicAlias -> {
                        if (topicAlias != null) duplicate(entryStart, "TopicAlias", 0x23)
                        topicAlias = prop
                    }
                    is MqttV5Property.MaximumQoS -> {
                        if (maximumQoS != null) duplicate(entryStart, "MaximumQoS", 0x24)
                        maximumQoS = prop
                    }
                    is MqttV5Property.RetainAvailable -> {
                        if (retainAvailable != null) duplicate(entryStart, "RetainAvailable", 0x25)
                        retainAvailable = prop
                    }
                    is MqttV5Property.UserProperty -> userProperties += prop
                    is MqttV5Property.MaximumPacketSize -> {
                        if (maximumPacketSize != null) duplicate(entryStart, "MaximumPacketSize", 0x27)
                        maximumPacketSize = prop
                    }
                    is MqttV5Property.WildcardSubscriptionAvailable -> {
                        if (wildcardSubscriptionAvailable != null) duplicate(entryStart, "WildcardSubscriptionAvailable", 0x28)
                        wildcardSubscriptionAvailable = prop
                    }
                    is MqttV5Property.SubscriptionIdentifiersAvailable -> {
                        if (subscriptionIdentifiersAvailable != null) duplicate(entryStart, "SubscriptionIdentifiersAvailable", 0x29)
                        subscriptionIdentifiersAvailable = prop
                    }
                    is MqttV5Property.SharedSubscriptionAvailable -> {
                        if (sharedSubscriptionAvailable != null) duplicate(entryStart, "SharedSubscriptionAvailable", 0x2A)
                        sharedSubscriptionAvailable = prop
                    }
                }
            }
        } finally {
            buffer.setLimit(outerLimit)
        }

        return V5PropertyBag(
            payloadFormatIndicator = payloadFormatIndicator,
            messageExpiryInterval = messageExpiryInterval,
            contentType = contentType,
            responseTopic = responseTopic,
            correlationData = correlationData,
            subscriptionIdentifiers = subscriptionIdentifiers,
            sessionExpiryInterval = sessionExpiryInterval,
            assignedClientIdentifier = assignedClientIdentifier,
            serverKeepAlive = serverKeepAlive,
            authenticationMethod = authenticationMethod,
            authenticationData = authenticationData,
            requestProblemInformation = requestProblemInformation,
            willDelayInterval = willDelayInterval,
            requestResponseInformation = requestResponseInformation,
            responseInformation = responseInformation,
            serverReference = serverReference,
            reasonString = reasonString,
            receiveMaximum = receiveMaximum,
            topicAliasMaximum = topicAliasMaximum,
            topicAlias = topicAlias,
            maximumQoS = maximumQoS,
            retainAvailable = retainAvailable,
            userProperties = userProperties,
            maximumPacketSize = maximumPacketSize,
            wildcardSubscriptionAvailable = wildcardSubscriptionAvailable,
            subscriptionIdentifiersAvailable = subscriptionIdentifiersAvailable,
            sharedSubscriptionAvailable = sharedSubscriptionAvailable,
        )
    }

    override fun encode(
        buffer: WriteBuffer,
        value: V5PropertyBag,
        context: EncodeContext,
    ) {
        val bodyLen = computeBodyByteCount(value)
        MqttRemainingLengthCodec.encode(buffer, bodyLen.toUInt(), context)
        for (prop in value.toList()) {
            MqttV5PropertyCodec.encode(buffer, prop, context)
        }
    }

    override fun wireSize(
        value: V5PropertyBag,
        context: EncodeContext,
    ): WireSize = WireSize.BackPatch

    override fun peekFrameSize(
        stream: StreamProcessor,
        baseOffset: Int,
    ): PeekResult = PeekResult.NoFraming

    private fun duplicate(
        position: Int,
        name: String,
        id: Int,
    ): Nothing =
        throw DecodeException(
            fieldPath = "V5PropertyBag.$name",
            bufferPosition = position,
            expected = "single occurrence (spec §2.2.2 — $name 0x${id.toString(16).padStart(2, '0')} appears at most once)",
            actual = "duplicate property identifier 0x${id.toString(16).padStart(2, '0')}",
        )

    private fun computeBodyByteCount(bag: V5PropertyBag): Int {
        var n = 0
        if (bag.payloadFormatIndicator != null) n += 2
        if (bag.messageExpiryInterval != null) n += 5
        bag.contentType?.let { n += 3 + it.value.utf8Length() }
        bag.responseTopic?.let { n += 3 + it.value.utf8Length() }
        bag.correlationData?.let { n += 3 + it.data.bytes.size }
        for (si in bag.subscriptionIdentifiers) n += 1 + vbiWidth(si.value)
        if (bag.sessionExpiryInterval != null) n += 5
        bag.assignedClientIdentifier?.let { n += 3 + it.value.utf8Length() }
        if (bag.serverKeepAlive != null) n += 3
        bag.authenticationMethod?.let { n += 3 + it.value.utf8Length() }
        bag.authenticationData?.let { n += 3 + it.data.bytes.size }
        if (bag.requestProblemInformation != null) n += 2
        if (bag.willDelayInterval != null) n += 5
        if (bag.requestResponseInformation != null) n += 2
        bag.responseInformation?.let { n += 3 + it.value.utf8Length() }
        bag.serverReference?.let { n += 3 + it.value.utf8Length() }
        bag.reasonString?.let { n += 3 + it.value.utf8Length() }
        if (bag.receiveMaximum != null) n += 3
        if (bag.topicAliasMaximum != null) n += 3
        if (bag.topicAlias != null) n += 3
        if (bag.maximumQoS != null) n += 2
        if (bag.retainAvailable != null) n += 2
        for (up in bag.userProperties) n += 1 + 2 + up.key.utf8Length() + 2 + up.value.utf8Length()
        if (bag.maximumPacketSize != null) n += 5
        if (bag.wildcardSubscriptionAvailable != null) n += 2
        if (bag.subscriptionIdentifiersAvailable != null) n += 2
        if (bag.sharedSubscriptionAvailable != null) n += 2
        return n
    }

    private fun vbiWidth(value: UInt): Int =
        when {
            value < 128u -> 1
            value < 16_384u -> 2
            value < 2_097_152u -> 3
            else -> 4
        }
}
