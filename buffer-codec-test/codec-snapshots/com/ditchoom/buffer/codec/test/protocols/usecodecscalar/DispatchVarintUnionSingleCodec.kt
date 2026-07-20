package com.ditchoom.buffer.codec.test.protocols.usecodecscalar

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
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

  override fun wireSize(`value`: DispatchVarintUnion.Single, context: EncodeContext): WireSize {
    val __vSize = when (val __s = QuicVarintCodec.wireSize(value.v, context)) {
      is WireSize.Exact -> __s.bytes
      WireSize.BackPatch -> return WireSize.BackPatch
    }
    return WireSize.Exact(0 + __vSize)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    val __vFrame = QuicVarintCodec.peekFrameSize(stream, baseOffset + 0)
    if (__vFrame !is PeekResult.Complete) {
      return __vFrame
    }
    val __total = 0 + __vFrame.bytes + 0
    return if (stream.available() - baseOffset >= __total) PeekResult.Complete(__total) else PeekResult.NeedsMoreData
  }
}
