package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.ProtocolMessage

@ProtocolMessage
data class SimpleHeader(
    val type: UByte,
    val length: UShort,
    val flags: UInt,
)
