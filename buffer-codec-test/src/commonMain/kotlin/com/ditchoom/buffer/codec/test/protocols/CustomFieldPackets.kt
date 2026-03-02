package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.test.annotations.PropertyBag
import com.ditchoom.buffer.codec.test.annotations.Repeated
import com.ditchoom.buffer.codec.test.annotations.VariableByteInteger

@ProtocolMessage
data class VbiPacket(
    val header: UByte,
    @VariableByteInteger val length: Int,
    val trailer: Byte,
)

@ProtocolMessage
data class RepeatedPacket(
    val count: UByte,
    @Repeated(countField = "count") val items: List<Short>,
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
