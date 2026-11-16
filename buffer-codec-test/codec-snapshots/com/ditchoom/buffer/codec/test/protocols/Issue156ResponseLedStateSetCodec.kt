package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FramePeek
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

  override fun sizeHint(`value`: Issue156Response.LedStateSet, context: EncodeContext): Int = 3

  override fun peekFrame(stream: StreamProcessor, baseOffset: Int): FramePeek = FramePeek.forDeclaredSize(3, stream.available() - baseOffset)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = peekFrame(stream, baseOffset).toPeekResult()
}
