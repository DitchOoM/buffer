package com.ditchoom.buffer.codec.test.protocols.ethernet

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

public object EtherTypeCodec : Codec<EtherType> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): EtherType {
    val raw = buffer.readUShort()
    return EtherType(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: EtherType,
    context: EncodeContext,
  ) {
    buffer.writeUShort(value.raw)
  }

  override fun wireSize(`value`: EtherType, context: EncodeContext): WireSize = WireSize.Exact(2)

  override fun sizeHint(`value`: EtherType, context: EncodeContext): Int = 2

  override fun peekFrame(stream: StreamProcessor, baseOffset: Int): FramePeek = FramePeek.forDeclaredSize(2, stream.available() - baseOffset)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = peekFrame(stream, baseOffset).toPeekResult()
}
