package com.ditchoom.buffer.codec.test.protocols.batch

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object HeaderByteCodec : Codec<HeaderByte> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): HeaderByte {
    val raw = buffer.readUByte()
    return HeaderByte(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: HeaderByte,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.raw)
  }

  override fun wireSize(`value`: HeaderByte, context: EncodeContext): WireSize = WireSize.Exact(1)

  override fun sizeHint(`value`: HeaderByte, context: EncodeContext): Int = 1

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 1) PeekResult.Complete(1) else PeekResult.NeedsMoreData
}
