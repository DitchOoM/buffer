package com.ditchoom.buffer.codec.test.protocols.websocket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.FrameSize
import com.ditchoom.buffer.codec.WireByteCount
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object FrameHeaderByte1Codec : Codec<FrameHeaderByte1> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): FrameHeaderByte1 {
    val raw = buffer.readUByte()
    return FrameHeaderByte1(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: FrameHeaderByte1,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.raw)
  }

  override fun wireBytes(`value`: FrameHeaderByte1, context: EncodeContext): WireByteCount = WireByteCount.exact(1L)

  override fun sizeHint(`value`: FrameHeaderByte1, context: EncodeContext): Int = 1

  override fun frameSize(stream: StreamProcessor, baseOffset: Int): FrameSize = FrameSize.declared(0, 1L)
}
