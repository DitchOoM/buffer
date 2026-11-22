package com.ditchoom.buffer.codec.test.protocols.slice14c

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object Slice14cTinyHeaderCodec : Codec<Slice14cTinyHeader> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): Slice14cTinyHeader {
    val raw = buffer.readUByte()
    return Slice14cTinyHeader(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: Slice14cTinyHeader,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.raw)
  }

  override fun wireBytes(`value`: Slice14cTinyHeader, context: EncodeContext): WireByteCount = WireByteCount.exact(1L)

  override fun sizeHint(`value`: Slice14cTinyHeader, context: EncodeContext): Int = 1

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 1L)
}
