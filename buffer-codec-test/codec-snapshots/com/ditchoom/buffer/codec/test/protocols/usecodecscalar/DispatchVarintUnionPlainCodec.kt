package com.ditchoom.buffer.codec.test.protocols.usecodecscalar

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object DispatchVarintUnionPlainCodec : Codec<DispatchVarintUnion.Plain> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): DispatchVarintUnion.Plain {
    val value = ZigZagUIntCodec.decode(buffer, context)
    return DispatchVarintUnion.Plain(value = value)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: DispatchVarintUnion.Plain,
    context: EncodeContext,
  ) {
    ZigZagUIntCodec.encode(buffer, value.value, context)
  }

  override fun wireSize(`value`: DispatchVarintUnion.Plain, context: EncodeContext): WireSize {
    val __valueSize = when (val __s = ZigZagUIntCodec.wireSize(value.value, context)) {
      is WireSize.Exact -> __s.bytes
      WireSize.BackPatch -> return WireSize.BackPatch
    }
    return WireSize.Exact(0 + __valueSize)
  }

  override fun sizeHint(`value`: DispatchVarintUnion.Plain, context: EncodeContext): Int = 0

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = PeekResult.NoFraming
}
