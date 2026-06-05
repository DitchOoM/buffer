package com.ditchoom.buffer.codec.test.protocols.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object Http3FrameHeadersCodec : Codec<Http3Frame.Headers> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Http3Frame.Headers {
    val frameType = Http3FrameTypeCodec.decode(buffer, context)
    val fieldSectionTag = buffer.readUShort()
    return Http3Frame.Headers(frameType = frameType, fieldSectionTag = fieldSectionTag)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Http3Frame.Headers,
    context: EncodeContext,
  ) {
    Http3FrameTypeCodec.encode(buffer, value.frameType, context)
    buffer.writeUShort(value.fieldSectionTag)
  }

  override fun wireSize(`value`: Http3Frame.Headers, context: EncodeContext): WireSize = WireSize.BackPatch

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult {
    val __frameTypeFrame = Http3FrameTypeCodec.peekFrameSize(stream, baseOffset + 0)
    if (__frameTypeFrame !is PeekResult.Complete) {
      return __frameTypeFrame
    }
    val __total = 0 + __frameTypeFrame.bytes + 2
    return if (stream.available() - baseOffset >= __total) PeekResult.Complete(__total) else PeekResult.NeedsMoreData
  }
}
