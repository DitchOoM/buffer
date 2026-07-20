package com.ditchoom.buffer.codec.test.protocols.http3

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

public object Http3FrameTypeCodec : Codec<Http3FrameType> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Http3FrameType {
    val raw = QuicVarintCodec.decode(buffer, context)
    return Http3FrameType(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Http3FrameType,
    context: EncodeContext,
  ) {
    QuicVarintCodec.encode(buffer, value.raw, context)
  }

  override fun wireSize(`value`: Http3FrameType, context: EncodeContext): WireSize {
    val __rawSize = when (val __s = QuicVarintCodec.wireSize(value.raw, context)) {
      is WireSize.Exact -> __s.bytes
      WireSize.BackPatch -> return WireSize.BackPatch
    }
    return WireSize.Exact(0 + __rawSize)
  }

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    val __rawFrame = QuicVarintCodec.peekFrameSize(stream, baseOffset + 0)
    if (__rawFrame !is PeekResult.Complete) {
      return __rawFrame
    }
    val __total = 0 + __rawFrame.bytes + 0
    return if (stream.available() - baseOffset >= __total) PeekResult.Complete(__total) else PeekResult.NeedsMoreData
  }
}
