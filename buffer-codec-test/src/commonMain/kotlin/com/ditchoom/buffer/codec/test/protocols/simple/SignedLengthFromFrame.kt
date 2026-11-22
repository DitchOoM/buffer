package com.ditchoom.buffer.codec.test.protocols.simple

import com.ditchoom.buffer.codec.annotations.Endianness
import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.ProtocolMessage

/**
 * `[length: signed Byte | flags | payload]`, a non-adjacent carrier like [WideLengthFrame].
 * A signed `Byte` carrier can declare a negative length, which must be rejected rather
 * than handed to a read.
 */
@ProtocolMessage(wireOrder = Endianness.Big)
data class SignedLengthFromFrame(
    val length: Byte,
    val flags: UByte,
    @LengthFrom("length") val payload: String,
)
