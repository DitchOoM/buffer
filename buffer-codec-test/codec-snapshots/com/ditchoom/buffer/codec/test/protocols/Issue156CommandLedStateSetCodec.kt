package com.ditchoom.buffer.codec.test.protocols

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object Issue156CommandLedStateSetCodec : Codec<Issue156Command.LedStateSet> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Issue156Command.LedStateSet {
    val ledId = buffer.readUByte()
    val duty = buffer.readUShort()
    return Issue156Command.LedStateSet(ledId = ledId, duty = duty)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Issue156Command.LedStateSet,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.ledId)
    buffer.writeUShort(value.duty)
  }

  override fun wireBytes(`value`: Issue156Command.LedStateSet, context: EncodeContext): WireByteCount = WireByteCount.exact(3L)

  override fun sizeHint(`value`: Issue156Command.LedStateSet, context: EncodeContext): Int = 3

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 3L)
}
