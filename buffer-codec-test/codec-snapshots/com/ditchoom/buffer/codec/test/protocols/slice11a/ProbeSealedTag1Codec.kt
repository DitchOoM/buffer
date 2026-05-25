package com.ditchoom.buffer.codec.test.protocols.slice11a

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object ProbeSealedTag1Codec : Codec<ProbeSealed.Tag1> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): ProbeSealed.Tag1 {
    val n = buffer.readUByte()
    return ProbeSealed.Tag1(n = n)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: ProbeSealed.Tag1,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.n)
  }

  override fun wireSize(`value`: ProbeSealed.Tag1, context: EncodeContext): WireSize = WireSize.Exact(1)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 1) PeekResult.Complete(1) else PeekResult.NeedsMoreData
}
