package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.LengthPrefix
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.test.annotations.PropertyBag
import com.ditchoom.buffer.codec.test.annotations.VariableByteInteger

@ProtocolMessage
data class VbiPacket(
    val header: UByte,
    @VariableByteInteger val length: Int,
    val trailer: Byte,
)

@ProtocolMessage
data class PropertyBagPacket(
    val version: UByte,
    @PropertyBag val properties: Map<Int, Int>,
)

@ProtocolMessage
data class MixedPacket(
    val id: UShort,
    @VariableByteInteger val remaining: Int,
    @LengthPrefixed val name: String,
    @PropertyBag val props: Map<Int, Int>,
)

// ──────────────────────── List<NestedMessage> test types ────────────────────────

@ProtocolMessage
data class ShortEntry(
    val value: Short,
)

@ProtocolMessage
data class RepeatedPacket(
    val count: UByte,
    @LengthFrom("count") val items: List<ShortEntry>,
)

@ProtocolMessage
data class Subscription(
    @LengthPrefixed(LengthPrefix.Byte) val topicFilter: String,
    val qos: UByte,
)

@ProtocolMessage
data class SubscribeByCount(
    val packetId: UShort,
    val count: UByte,
    @LengthFrom("count") val subscriptions: List<Subscription>,
)

@ProtocolMessage
data class SubscribeRemaining(
    val packetId: UShort,
    @RemainingBytes val subscriptions: List<Subscription>,
)

@ProtocolMessage
data class PrefixedEntries(
    val header: UByte,
    @LengthPrefixed(LengthPrefix.Byte) val items: List<ShortEntry>,
)
