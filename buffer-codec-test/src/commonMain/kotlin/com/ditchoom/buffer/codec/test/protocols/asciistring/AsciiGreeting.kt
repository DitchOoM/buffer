package com.ditchoom.buffer.codec.test.protocols.asciistring

import com.ditchoom.buffer.codec.AsciiStringCodec
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.UseCodec

/**
 * J.M.7.b fixture — exercises the slice 15a `@LengthPrefixed
 * @UseCodec(C::class) val: String` shape with the new built-in
 * [AsciiStringCodec]. Wire layout: `[2-byte UShort BE prefix | ASCII
 * body bytes]`.
 */
@ProtocolMessage
data class AsciiGreeting(
    @LengthPrefixed @UseCodec(AsciiStringCodec::class) val command: String,
)
