package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

@ProtocolMessage
data class SimplePayloadMessage<@Payload P>(
    val id: UShort,
    @LengthPrefixed val data: P,
)
