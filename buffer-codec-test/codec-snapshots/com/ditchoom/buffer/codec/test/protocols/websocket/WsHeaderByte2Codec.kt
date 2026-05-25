package com.ditchoom.buffer.codec.test.protocols.websocket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.PeekResult
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.stream.StreamProcessor
import kotlin.Int

public object WsHeaderByte2Codec : Codec<WsHeaderByte2> {
  override fun decode(buffer: ReadBuffer, context: DecodeContext): WsHeaderByte2 {
    val raw = buffer.readUByte()
    return WsHeaderByte2(raw = raw)
  }

  override fun encode(
    buffer: WriteBuffer,
    `value`: WsHeaderByte2,
    context: EncodeContext,
  ) {
    buffer.writeUByte(value.raw)
  }

  override fun wireSize(`value`: WsHeaderByte2, context: EncodeContext): WireSize = WireSize.Exact(1)

  override fun peekFrameSize(stream: StreamProcessor, baseOffset: Int): PeekResult = if (stream.available() - baseOffset >= 1) PeekResult.Complete(1) else PeekResult.NeedsMoreData
}
