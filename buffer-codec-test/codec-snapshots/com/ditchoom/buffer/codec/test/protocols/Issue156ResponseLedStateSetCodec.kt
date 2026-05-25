package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object Issue156ResponseLedStateSetCodec : Codec<Issue156Response.LedStateSet> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Issue156Response.LedStateSet {
    val ledId = buffer.readUByte()
    val duty = buffer.readUShort()
    return Issue156Response.LedStateSet(ledId = ledId, duty = duty)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Issue156Response.LedStateSet,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.ledId)
    buffer.writeUShort(value.duty)
  }

  override fun wireSize(`value`: Issue156Response.LedStateSet, context: EncodeContext): WireSize = WireSize.Exact(3)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 3) PeekResult.Complete(3) else PeekResult.NeedsMoreData
}
