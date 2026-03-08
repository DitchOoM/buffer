package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.WireBytes

@ProtocolMessage
data class CustomWidthMessage(
    val flags: UByte,
    @WireBytes(3) val signedValue: Int,
    @WireBytes(3) val unsignedValue: UInt,
    val trailer: UByte,
)
