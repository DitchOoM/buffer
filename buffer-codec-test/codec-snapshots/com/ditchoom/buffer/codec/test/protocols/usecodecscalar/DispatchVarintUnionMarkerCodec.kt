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

public object DispatchVarintUnionMarkerCodec : Codec<DispatchVarintUnion.Marker> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): DispatchVarintUnion.Marker = DispatchVarintUnion.Marker

  override fun encode(
    buffer: WriteBuffer,
    `value`: DispatchVarintUnion.Marker,
    context: EncodeContext,
  ) {
  }

  override fun wireBytes(`value`: DispatchVarintUnion.Marker, context: EncodeContext): WireByteCount = WireByteCount.exact(0L)

  override fun sizeHint(`value`: DispatchVarintUnion.Marker, context: EncodeContext): Int = 0

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 0L)
}
