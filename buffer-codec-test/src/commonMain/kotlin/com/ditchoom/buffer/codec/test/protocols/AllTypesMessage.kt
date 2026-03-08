package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

@ProtocolMessage
data class AllTypesMessage(
    val byteVal: Byte,
    val ubyteVal: UByte,
    val shortVal: Short,
    val ushortVal: UShort,
    val intVal: Int,
    val uintVal: UInt,
    val longVal: Long,
    val ulongVal: ULong,
    val floatVal: Float,
    val doubleVal: Double,
    val boolVal: Boolean,
    @LengthPrefixed val stringVal: String,
)
