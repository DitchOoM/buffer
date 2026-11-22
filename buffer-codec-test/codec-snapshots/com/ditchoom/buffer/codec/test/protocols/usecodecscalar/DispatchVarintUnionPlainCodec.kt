package com.ditchoom.buffer.codec.test.protocols.usecodecscalar

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
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

  override fun wireBytes(`value`: DispatchVarintUnion.Plain, context: EncodeContext): WireByteCount {
    val __valueSize = ZigZagUIntCodec.wireBytes(value.value, context).fold(exact = { it }, backPatch = { return WireByteCount.BackPatch })
    return WireByteCount.exact(0L + __valueSize)
  }

  override fun sizeHint(`value`: DispatchVarintUnion.Plain, context: EncodeContext): Int = 0

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.Unframed
}
