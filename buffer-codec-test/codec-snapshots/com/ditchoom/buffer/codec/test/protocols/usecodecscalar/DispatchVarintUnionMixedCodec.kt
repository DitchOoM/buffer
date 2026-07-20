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

public object DispatchVarintUnionMixedCodec : Codec<DispatchVarintUnion.Mixed> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): DispatchVarintUnion.Mixed {
    val v = QuicVarintCodec.decode(buffer, context)
    val flag = buffer.readByte() != 0.toByte()
    return DispatchVarintUnion.Mixed(v = v, flag = flag)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: DispatchVarintUnion.Mixed,
    context: EncodeContext,
  ) {
    QuicVarintCodec.encode(buffer, value.v, context)
    buffer.writeByte(if (value.flag) 1.toByte() else 0.toByte())
  }

  override fun wireSize(`value`: DispatchVarintUnion.Mixed, context: EncodeContext): WireSize {
    val __vSize = when (val __s = QuicVarintCodec.wireSize(value.v, context)) {
      is WireSize.Exact -> __s.bytes
      WireSize.BackPatch -> return WireSize.BackPatch
    }
    return WireSize.Exact(1 + __vSize)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    val __vFrame = QuicVarintCodec.peekFrameSize(stream, baseOffset + 0)
    if (__vFrame !is PeekResult.Complete) {
      return __vFrame
    }
    val __total = 0 + __vFrame.bytes + 1
    return if (stream.available() - baseOffset >= __total) PeekResult.Complete(__total) else PeekResult.NeedsMoreData
  }
}
