package com.ditchoom.buffer.codec.test.protocols.usecodecscalar

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.codec.test.protocols.quic.QuicVarintCodec
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object DispatchVarintUnionSingleCodec : Codec<DispatchVarintUnion.Single> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): DispatchVarintUnion.Single {
    val v = QuicVarintCodec.decode(buffer, context)
    return DispatchVarintUnion.Single(v = v)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: DispatchVarintUnion.Single,
    context: EncodeContext,
  ) {
    QuicVarintCodec.encode(buffer, value.v, context)
  }

  override fun wireBytes(`value`: DispatchVarintUnion.Single, context: EncodeContext): WireByteCount {
    val __vSize = QuicVarintCodec.encodedLength(value.v).toLong()
    return WireByteCount.exact(0L + __vSize)
  }

  override fun sizeHint(`value`: DispatchVarintUnion.Single, context: EncodeContext): Int = 0

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize {
    val __vWidth = QuicVarintCodec.frameSize(stream, baseOffset + 0).fold(declared = { __h, __b -> __h + __b }, needsMoreData = { return FrameSize.NeedsMoreData }, unframed = { return FrameSize.Unframed })
    return FrameSize.declared(0, 0L + __vWidth)
  }
}
