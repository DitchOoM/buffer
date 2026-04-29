package com.ditchoom.buffer.codec.test.functions

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext

object PropertyBagCodec : Codec<Map<Int, Int>> {
    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): Map<Int, Int> = buffer.readPropertyBag()

    override fun encode(
        buffer: WriteBuffer,
        value: Map<Int, Int>,
        context: EncodeContext,
    ) {
        buffer.writePropertyBag(value)
    }

    override fun wireSize(
        value: Map<Int, Int>,
        context: EncodeContext,
    ): Int = propertyBagSize(value)
}
